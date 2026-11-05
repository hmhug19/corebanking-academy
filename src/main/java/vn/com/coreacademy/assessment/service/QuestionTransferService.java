package vn.com.coreacademy.assessment.service;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import vn.com.coreacademy.assessment.constants.AssessmentCodes.AuditAction;
import vn.com.coreacademy.assessment.constants.AssessmentCodes.Difficulty;
import vn.com.coreacademy.assessment.constants.AssessmentCodes.ItemStatus;
import vn.com.coreacademy.assessment.constants.AssessmentCodes.QuestionType;
import vn.com.coreacademy.assessment.constants.AssessmentLimits;
import vn.com.coreacademy.assessment.dto.AssessmentRequests;
import vn.com.coreacademy.assessment.dto.AssessmentViews;
import vn.com.coreacademy.assessment.entity.Competency;
import vn.com.coreacademy.assessment.entity.Question;
import vn.com.coreacademy.assessment.entity.QuestionOption;
import vn.com.coreacademy.assessment.exception.AssessmentResponseCode;
import vn.com.coreacademy.assessment.importing.QuestionWorkbook;
import vn.com.coreacademy.assessment.importing.QuestionWorkbook.Parsed;
import vn.com.coreacademy.assessment.importing.QuestionWorkbook.QuestionRow;
import vn.com.coreacademy.assessment.repository.AssessmentRepository;
import vn.com.coreacademy.assessment.repository.QuestionRepository;
import vn.com.coreacademy.assessment.repository.custom.QuestionRepositoryCustom;
import vn.com.coreacademy.assessment.service.QuestionService.Bank;
import vn.com.coreacademy.audit.service.IdentityAuditService;
import vn.com.coreacademy.common.dto.FieldViolation;
import vn.com.coreacademy.common.exception.BusinessException;
import vn.com.coreacademy.common.validation.ValidationErrors;
import vn.com.coreacademy.identity.constants.RoleCodes;
import vn.com.coreacademy.learning.service.LearningAssessmentAccess;
import vn.com.coreacademy.learning.service.LearningWriteLock;

/**
 * Nhập/xuất ngân hàng câu hỏi bằng Excel. Mỗi dòng được chuyển thành đúng QuestionRequest của API tạo câu và đi qua
 * cùng Bean Validation/quy tắc phương án, nên nhập tệp không tạo được dữ liệu mà API từ chối. Cả lô thành công hoặc
 * không ghi dòng nào.
 */
@Service
@RequiredArgsConstructor
public class QuestionTransferService {
    private static final Map<String, String> COLUMNS = Map.of("code", "ma", "content", "noi_dung",
        "questionType", "loai", "options", "phuong_an", "explanation", "giai_thich", "difficulty", "do_kho",
        "competencyId", "nhom_kien_thuc", "points", "diem", "status", "trang_thai");
    private static final Map<String, String> TYPES = Map.of("single", QuestionType.SINGLE, "mot",
        QuestionType.SINGLE, "một", QuestionType.SINGLE, "multiple", QuestionType.MULTIPLE, "nhieu",
        QuestionType.MULTIPLE, "nhiều", QuestionType.MULTIPLE);
    private static final Map<String, String> DIFFICULTIES = Map.of("easy", Difficulty.EASY, "dễ", Difficulty.EASY,
        "de", Difficulty.EASY, "medium", Difficulty.MEDIUM, "trung bình", Difficulty.MEDIUM, "trung binh",
        Difficulty.MEDIUM, "hard", Difficulty.HARD, "khó", Difficulty.HARD, "kho", Difficulty.HARD);
    private static final BigDecimal MAX_POINTS = new BigDecimal("9999.99");

    private final LearningAssessmentAccess learning;
    private final AssessmentRepository repository;
    private final QuestionRepository questions;
    private final QuestionService questionService;
    private final QuestionWorkbook workbook;
    private final LearningWriteLock lock;
    private final IdentityAuditService audit;
    private final Validator validator;
    private final PlatformTransactionManager transactionManager;

    private record Planned(QuestionRow row, AssessmentRequests.QuestionRequest request, Question target) {
    }

    /** Mẫu không chứa dữ liệu; chỉ người có vai trò biên soạn (giảng viên/quản trị) được tải. */
    @Transactional(readOnly = true)
    public byte[] template() {
        boolean author = learning.actor().grants().stream().anyMatch(g -> Set.of(RoleCodes.SUPER_ADMIN,
            RoleCodes.ADMIN, RoleCodes.INSTRUCTOR).contains(g.roleCode()));
        if (!author) {
            throw new AccessDeniedException("Question template requires an authoring role");
        }
        return workbook.template();
    }

    /**
     * Parse tệp ngoài transaction. dryRun chỉ kiểm tra (không khóa, không ghi). Nhập thật giữ write lock, kiểm tra lại
     * quyền, tham chiếu, mã trùng rồi ghi toàn bộ cùng audit trong một transaction. overwrite=true cho phép ghi đè câu
     * cùng mã trong cùng chương trình; courseId cũ chỉ được ghi đè trong cùng khóa.
     */
    public AssessmentViews.QuestionImportResult importFile(Long courseId, MultipartFile file, boolean dryRun,
                                                          boolean overwrite) throws IOException {
        return importFile(courseId, null, file, dryRun, overwrite);
    }

    public AssessmentViews.QuestionImportResult importFile(Long courseId, Long programId, MultipartFile file,
                                                          boolean dryRun, boolean overwrite) throws IOException {
        questionService.requireBank(courseId, programId);
        Parsed parsed = workbook.read(file);
        var transaction = new TransactionTemplate(transactionManager);
        transaction.setReadOnly(dryRun);
        return transaction.execute(status -> {
            if (!dryRun) {
                lock.acquire();
            }
            Bank bank = questionService.requireBank(courseId, programId);
            String actor = learning.actor().person().id();
            List<Planned> plan = validate(bank, programId != null, parsed, overwrite);
            int toUpdate = (int) plan.stream().filter(p -> p.target() != null).count();
            int toCreate = plan.size() - toUpdate;
            if (dryRun) {
                return new AssessmentViews.QuestionImportResult(true, plan.size(), toCreate, toUpdate, 0, 0);
            }
            for (Planned item : plan) {
                if (item.target() == null) {
                    questionService.insert(bank, item.request(), actor);
                } else {
                    questionService.overwrite(item.target(), bank, item.request(), actor);
                }
            }
            audit.record(actor, AuditAction.QUESTIONS_IMPORTED, programId != null ? "program" : "course",
                (programId != null ? bank.programId() : bank.courseId()).toString(), bank.ownerOrgId(),
                Map.of("created", toCreate, "updated", toUpdate, "overwrite", overwrite));
            return new AssessmentViews.QuestionImportResult(false, plan.size(), toCreate, toUpdate, toCreate,
                toUpdate);
        });
    }

    /** Xuất cùng định dạng tệp nhập (sắp theo ID), tối đa MAX_EXPORT_ROWS câu, theo bộ lọc của danh sách câu hỏi. */
    @Transactional(readOnly = true)
    public byte[] export(Long courseId, String q, Long competencyId, String questionType, String difficulty,
                         String status) {
        learning.requireManagedCourse(courseId);
        QuestionService.requireCode("questionType", questionType, Set.of(QuestionType.SINGLE, QuestionType.MULTIPLE));
        QuestionService.requireCode("difficulty", difficulty, Set.of(Difficulty.EASY, Difficulty.MEDIUM,
            Difficulty.HARD));
        QuestionService.requireCode("status", status, Set.of(ItemStatus.ACTIVE, ItemStatus.INACTIVE));
        var filter = new QuestionRepositoryCustom.Filter(courseId, q, null, competencyId, questionType, difficulty,
            status);
        List<Long> ids = questions.search(filter, PageRequest.of(0, AssessmentLimits.MAX_EXPORT_ROWS + 1,
            Sort.by("id"))).getContent().stream().map(AssessmentViews.QuestionSummary::id).toList();
        if (ids.size() > AssessmentLimits.MAX_EXPORT_ROWS) {
            throw new BusinessException(AssessmentResponseCode.EXPORT_TOO_LARGE);
        }

        Map<Long, Question> byId = repository.questions(ids).stream()
            .collect(Collectors.toMap(Question::getId, Function.identity()));
        Map<Long, List<QuestionOption>> options = repository.options(ids).stream()
            .collect(Collectors.groupingBy(QuestionOption::getQuestionId));
        Map<Long, String> competencyCodes = repository.competencies(byId.values().stream()
            .map(Question::getCompetencyId).distinct().toList()).stream()
            .collect(Collectors.toMap(Competency::getId, Competency::getCode));
        List<QuestionRow> rows = new ArrayList<>();
        for (Long id : ids) {
            Question question = byId.get(id);
            List<QuestionOption> choices = options.getOrDefault(id, List.of());
            List<String> letters = new ArrayList<>();
            for (int i = 0; i < choices.size(); i++) {
                if (choices.get(i).getCorrect()) {
                    letters.add(String.valueOf((char) ('A' + i)));
                }
            }
            rows.add(new QuestionRow(rows.size() + 2, question.getCode(), question.getContent(),
                question.getQuestionType(), choices.stream().map(QuestionOption::getContent)
                .collect(Collectors.joining(QuestionWorkbook.OPTION_SEPARATOR)),
                String.join(QuestionWorkbook.OPTION_SEPARATOR, letters), question.getExplanation(),
                question.getDifficulty(), competencyCodes.get(question.getCompetencyId()),
                question.getPoints().stripTrailingZeros().toPlainString(), question.getStatus()));
        }
        return workbook.write(rows);
    }

    private List<Planned> validate(Bank bank, boolean programScoped, Parsed parsed, boolean overwrite) {
        List<FieldViolation> errors = new ArrayList<>(parsed.errors());
        Map<String, Competency> competencies = repository.competenciesByCodes(parsed.rows().stream()
            .map(QuestionRow::competency).filter(code -> !code.isBlank()).distinct().toList()).stream()
            .collect(Collectors.toMap(Competency::getCode, Function.identity()));
        Map<String, Question> existing = repository.questionsByCodes(parsed.rows().stream().map(QuestionRow::code)
            .filter(code -> !code.isBlank()).distinct().toList()).stream()
            .collect(Collectors.toMap(Question::getCode, Function.identity()));

        Map<String, QuestionRow> seen = new HashMap<>();
        List<Planned> plan = new ArrayList<>();
        for (QuestionRow row : parsed.rows()) {
            int before = errors.size();
            String prefix = QuestionWorkbook.SHEET + "[" + row.row() + "]";
            AssessmentRequests.QuestionRequest request = toRequest(bank, programScoped, row, competencies, prefix,
                errors);
            Question target = null;
            if (!row.code().isBlank()) {
                QuestionRow previous = seen.putIfAbsent(row.code(), row);
                if (previous != null) {
                    issue(errors, prefix + ".ma", "DUPLICATE_IN_FILE", "Mã câu hỏi bị lặp trong tệp.");
                    issue(errors, QuestionWorkbook.SHEET + "[" + previous.row() + "].ma", "DUPLICATE_IN_FILE",
                        "Mã câu hỏi bị lặp trong tệp.");
                }
                Question found = existing.get(row.code());
                boolean sameBank = found != null && (programScoped
                    ? Objects.equals(found.getProgramId(), bank.programId())
                    : Objects.equals(found.getCourseId(), bank.courseId()));
                if (found != null && (!overwrite || !sameBank)) {
                    issue(errors, prefix + ".ma", "ALREADY_EXISTS", sameBank
                        ? "Mã câu hỏi đã có trong phạm vi đã chọn; chọn chế độ ghi đè để cập nhật."
                        : "Mã câu hỏi đã được dùng ở ngân hàng khác, không thể ghi đè.");
                } else {
                    target = found;
                }
            }
            if (request != null && errors.size() == before) {
                plan.add(new Planned(row, request, target));
            }
        }
        if (!errors.isEmpty()) {
            throw new BusinessException(AssessmentResponseCode.IMPORT_INVALID,
                errors.subList(0, Math.min(errors.size(), AssessmentLimits.MAX_IMPORT_ERRORS)));
        }
        return plan;
    }

    /** Chuẩn hóa một dòng thành request API; lỗi gắn đúng cột Excel. Trả null nếu dòng không dựng được request. */
    private AssessmentRequests.QuestionRequest toRequest(Bank bank, boolean programScoped, QuestionRow row,
                                                         Map<String, Competency> competencies, String prefix,
                                                         List<FieldViolation> errors) {
        int before = errors.size();
        String type = TYPES.get(row.type().toLowerCase(Locale.ROOT));
        if (type == null) {
            issue(errors, prefix + ".loai", "INVALID_VALUE", "Loại phải là SINGLE hoặc MULTIPLE (mot/nhieu).");
        }
        String difficulty = row.difficulty().isBlank() ? Difficulty.MEDIUM
            : DIFFICULTIES.get(row.difficulty().toLowerCase(Locale.ROOT));
        if (difficulty == null) {
            issue(errors, prefix + ".do_kho", "INVALID_VALUE", "Độ khó phải là EASY, MEDIUM hoặc HARD.");
        }
        String status = row.status().isBlank() ? ItemStatus.ACTIVE : row.status().toUpperCase(Locale.ROOT);
        if (!Set.of(ItemStatus.ACTIVE, ItemStatus.INACTIVE).contains(status)) {
            issue(errors, prefix + ".trang_thai", "INVALID_VALUE", "Trạng thái phải là ACTIVE hoặc INACTIVE.");
        }
        Competency competency = competencies.get(row.competency());
        if (competency == null || !ItemStatus.ACTIVE.equals(competency.getStatus())) {
            issue(errors, prefix + ".nhom_kien_thuc", row.competency().isBlank() ? "REQUIRED" : "INVALID_REFERENCE",
                "Nhập mã nhóm năng lực đang sử dụng.");
        }
        BigDecimal points = points(row.points());
        if (points == null) {
            issue(errors, prefix + ".diem", "OUT_OF_RANGE",
                "Điểm phải là số dương, tối đa 9999.99, 2 chữ số thập phân.");
        }
        List<String> texts = row.options().isBlank() ? List.of()
            : Arrays.stream(row.options().split("\\|", -1)).map(String::trim).toList();
        if (texts.size() < AssessmentLimits.MIN_OPTIONS || texts.size() > AssessmentLimits.MAX_OPTIONS
            || texts.stream().anyMatch(String::isEmpty)) {
            issue(errors, prefix + ".phuong_an", "INVALID_VALUE",
                "Cần 2-10 phương án có nội dung, phân tách bằng ký tự |.");
        }
        List<Integer> answers = answers(row.answers(), texts.size());
        if (answers == null) {
            issue(errors, prefix + ".dap_an_dung", "INVALID_VALUE",
                "Đáp án đúng là chữ cái theo thứ tự phương án, phân tách bằng |, ví dụ A hoặc A|C.");
        }
        if (errors.size() > before) {
            return null;
        }

        List<AssessmentRequests.OptionInput> options = new ArrayList<>();
        for (int i = 0; i < texts.size(); i++) {
            options.add(new AssessmentRequests.OptionInput(null, texts.get(i), answers.contains(i)));
        }
        var request = new AssessmentRequests.QuestionRequest(bank.courseId(), null, competency.getId(),
            row.code().isBlank() ? null : row.code(), type, row.content(),
            row.explanation().isBlank() ? null : row.explanation(), difficulty, points, status, options,
            programScoped ? bank.programId() : null);
        for (ConstraintViolation<AssessmentRequests.QuestionRequest> violation : validator.validate(request)) {
            String property = violation.getPropertyPath().toString().replaceAll("\\[\\d+].*", "");
            FieldViolation mapped = ValidationErrors.fromConstraint(property,
                violation.getConstraintDescriptor().getAnnotation().annotationType().getSimpleName());
            issue(errors, prefix + "." + COLUMNS.getOrDefault(property, "noi_dung"), mapped.code(), mapped.message());
        }
        if (errors.size() > before) {
            return null;
        }
        try {
            QuestionService.validateOptions(request, List.of());
        } catch (BusinessException exception) {
            String message = exception.getErrors().isEmpty() ? "Đáp án đúng không hợp lệ."
                : exception.getErrors().get(0).message();
            issue(errors, prefix + ".dap_an_dung", "INVALID_VALUE", message);
            return null;
        }
        return request;
    }

    private static BigDecimal points(String value) {
        if (value.isBlank()) {
            return BigDecimal.ONE;
        }
        try {
            BigDecimal points = new BigDecimal(value.replace(',', '.'));
            return points.signum() > 0 && points.compareTo(MAX_POINTS) <= 0 && points.stripTrailingZeros().scale() <= 2
                ? points : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    /** Chữ cái A.. theo thứ tự phương án, không trùng; trả chỉ số 0-based hoặc null nếu sai. */
    private static List<Integer> answers(String value, int optionCount) {
        if (value.isBlank()) {
            return null;
        }
        List<Integer> indexes = new ArrayList<>();
        for (String part : value.split("\\|", -1)) {
            String letter = part.trim().toUpperCase(Locale.ROOT);
            if (letter.length() != 1) {
                return null;
            }
            int index = letter.charAt(0) - 'A';
            if (index < 0 || index >= Math.max(optionCount, 1) || indexes.contains(index)) {
                return null;
            }
            indexes.add(index);
        }
        return indexes;
    }

    private static void issue(List<FieldViolation> errors, String field, String code, String message) {
        errors.add(new FieldViolation(field, code, message));
    }
}

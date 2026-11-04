package vn.com.coreacademy.assessment.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.com.coreacademy.assessment.constants.AssessmentCodes.AuditAction;
import vn.com.coreacademy.assessment.constants.AssessmentCodes.Difficulty;
import vn.com.coreacademy.assessment.constants.AssessmentCodes.ItemStatus;
import vn.com.coreacademy.assessment.constants.AssessmentCodes.QuestionType;
import vn.com.coreacademy.assessment.dto.AssessmentRequests;
import vn.com.coreacademy.assessment.dto.AssessmentViews;
import vn.com.coreacademy.assessment.entity.Competency;
import vn.com.coreacademy.assessment.entity.Question;
import vn.com.coreacademy.assessment.entity.QuestionOption;
import vn.com.coreacademy.assessment.exception.AssessmentResponseCode;
import vn.com.coreacademy.assessment.mapper.AssessmentMapper;
import vn.com.coreacademy.assessment.repository.AssessmentRepository;
import vn.com.coreacademy.assessment.repository.QuestionRepository;
import vn.com.coreacademy.assessment.repository.custom.QuestionRepositoryCustom;
import vn.com.coreacademy.audit.service.IdentityAuditService;
import vn.com.coreacademy.common.dto.PageResponse;
import vn.com.coreacademy.common.exception.BusinessException;
import vn.com.coreacademy.common.web.PageQuery;
import vn.com.coreacademy.learning.service.LearningAssessmentAccess;
import vn.com.coreacademy.learning.service.LearningAssessmentAccess.ManagedCourse;
import vn.com.coreacademy.learning.service.LearningWriteLock;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class QuestionService {
    private static final Set<String> SORTS = Set.of("id", "code", "updateTime");

    private final LearningAssessmentAccess learning;
    private final AssessmentRepository repository;
    private final QuestionRepository questions;
    private final AssessmentMapper mapper;
    private final LearningWriteLock lock;
    private final IdentityAuditService audit;

    public PageResponse<AssessmentViews.QuestionSummary> search(Long courseId, Long programId, String q, Long lessonId,
                                                                Long competencyId, String questionType,
                                                                String difficulty, String status, int page,
                                                                int size, String sort) {
        if (programId != null) {
            if (courseId != null) {
                learning.requireCourseProgramBank(courseId, programId);
            } else {
                learning.requireManagedProgram(programId);
            }
        } else if (courseId != null) {
            learning.requireManagedCourse(courseId);
        } else {
            throw BusinessException.validation("programId", "REQUIRED", "Chọn chương trình của ngân hàng câu hỏi.");
        }
        requireCode("questionType", questionType, Set.of(QuestionType.SINGLE, QuestionType.MULTIPLE));
        requireCode("difficulty", difficulty, Set.of(Difficulty.EASY, Difficulty.MEDIUM, Difficulty.HARD));
        requireCode("status", status, Set.of(ItemStatus.ACTIVE, ItemStatus.INACTIVE));
        var filter = new QuestionRepositoryCustom.Filter(courseId, q, lessonId, competencyId, questionType,
            difficulty, status, programId);
        return PageResponse.from(questions.search(filter, PageQuery.of(page, size, sort, SORTS)));
    }

    public AssessmentViews.QuestionDetail get(Long id) {
        Question question = requireQuestion(id);
        questionBank(question, false);
        return detail(question);
    }

    /** Tạo câu hỏi theo chương trình; courseId chỉ còn là đường tương thích cho client cũ. */
    @Transactional
    public AssessmentViews.QuestionDetail create(AssessmentRequests.QuestionRequest request) {
        Bank bank;
        if (request.programId() != null) {
            var program = learning.requireManagedProgram(request.programId());
            if (request.courseId() != null) {
                learning.requireCourseProgramBank(request.courseId(), program.id());
            }
            bank = new Bank(program.id(), request.courseId(), program.ownerOrgId());
        } else if (request.courseId() != null) {
            bank = courseBank(learning.requireManagedCourse(request.courseId()));
        } else {
            throw BusinessException.validation("programId", "REQUIRED", "Chọn chương trình của ngân hàng câu hỏi.");
        }
        String actor = learning.actor().person().id();
        lock.acquire();
        return detail(insert(bank, request, actor));
    }

    /**
     * Ghi câu hỏi mới sau khi kiểm tra tham chiếu, phương án và mã; dùng chung cho API tạo và nhập Excel. Bên gọi
     * đã kiểm quyền khóa và giữ write lock trong transaction hiện tại.
     */
    Question insert(ManagedCourse course, AssessmentRequests.QuestionRequest request, String actor) {
        return insert(courseBank(course), request, actor);
    }

    private Question insert(Bank bank, AssessmentRequests.QuestionRequest request, String actor) {
        validateReferences(bank.courseId(), request, null);
        List<AssessmentRequests.OptionInput> options = validateOptions(request, List.of());
        String code = blankToNull(request.code());
        if (code != null && repository.questionCodeExists(code, null)) {
            throw duplicateCode();
        }

        Question question = new Question();
        question.setCourseId(bank.courseId());
        question.setProgramId(bank.programId());
        question.setCode(code);
        apply(question, request);
        repository.save(question, actor);
        repository.flush();
        if (code == null) {
            question.setCode("Q" + question.getId());
        }
        for (int i = 0; i < options.size(); i++) {
            QuestionOption option = new QuestionOption();
            option.setQuestionId(question.getId());
            option.setContent(options.get(i).content().trim());
            option.setCorrect(options.get(i).correct());
            option.setSortNo(i);
            repository.persist(option);
        }
        repository.flush();

        audit.record(actor, AuditAction.QUESTION_CREATED, "question", question.getId().toString(),
            bank.ownerOrgId(), Map.of("code", question.getCode()));
        return question;
    }

    /**
     * Cập nhật câu hỏi và thay toàn bộ danh sách phương án: phương án có id được sửa tại chỗ, phương án mới được
     * thêm, phương án cũ không còn trong request bị xóa mềm. Nếu câu đã từng được phát đề và nội dung chấm thay
     * đổi thì tăng content_version; lượt thi cũ vẫn chấm theo snapshot.
     */
    @Transactional
    public AssessmentViews.QuestionDetail update(Long id, Long expectedVersion,
                                                 AssessmentRequests.QuestionRequest request) {
        Question question = requireQuestion(id);
        Bank bank = questionBank(question, request.programId() != null);
        String actor = learning.actor().person().id();
        lock.acquire();

        if (!Objects.equals(question.getRowVersion(), expectedVersion)) {
            throw new BusinessException(AssessmentResponseCode.CONFLICT);
        }
        return detail(overwrite(question, bank, request, actor));
    }

    /**
     * Ghi đè câu hỏi hiện có (API cập nhật và nhập Excel chế độ ghi đè). Bên gọi đã kiểm quyền, version và giữ
     * write lock trong transaction hiện tại.
     */
    Question overwrite(Question question, ManagedCourse course, AssessmentRequests.QuestionRequest request,
                       String actor) {
        return overwrite(question, courseBank(course), request, actor);
    }

    private Question overwrite(Question question, Bank bank, AssessmentRequests.QuestionRequest request,
                               String actor) {
        Long id = question.getId();
        if (request.programId() != null && !request.programId().equals(question.getProgramId())) {
            throw BusinessException.validation("programId", "INVALID_VALUE",
                "Không thể chuyển câu hỏi sang chương trình khác.");
        }
        if (request.courseId() != null && !request.courseId().equals(question.getCourseId())) {
            throw BusinessException.validation("courseId", "INVALID_VALUE", "Không thể chuyển câu hỏi sang khóa khác.");
        }
        validateReferences(bank.courseId(), request, question);
        List<QuestionOption> existing = repository.options(List.of(id));
        List<AssessmentRequests.OptionInput> inputs = validateOptions(request, existing);
        String code = blankToNull(request.code());
        if (code != null && !code.equals(question.getCode()) && repository.questionCodeExists(code, id)) {
            throw duplicateCode();
        }

        boolean gradingChanged = !Objects.equals(question.getContent(), request.content().trim())
            || !Objects.equals(question.getQuestionType(), request.questionType())
            || !Objects.equals(question.getExplanation(), request.explanation())
            || optionsChanged(existing, inputs);
        if (gradingChanged && repository.questionServed(id)) {
            question.setContentVersion(question.getContentVersion() + 1);
        }

        if (code != null) {
            question.setCode(code);
        }
        apply(question, request);
        mergeOptions(id, existing, inputs);
        repository.save(question, actor);
        repository.touchVersion(question);
        repository.flush();

        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("code", question.getCode());
        changes.put("status", question.getStatus());
        changes.put("contentVersion", question.getContentVersion());
        if (ItemStatus.INACTIVE.equals(question.getStatus()) && repository.publishedQuizzesUsing(List.of(id)).stream()
            .anyMatch(quiz -> repository.activePoolSize(quiz.getId()) < quiz.getQuestionCount())) {
            throw new BusinessException(AssessmentResponseCode.QUIZ_NOT_READY);
        }
        audit.record(actor, AuditAction.QUESTION_UPDATED, "question", id.toString(), bank.ownerOrgId(), changes);
        return question;
    }

    /**
     * Đổi trạng thái hàng loạt. Ngưng sử dụng bị từ chối nếu làm đề đã xuất bản thiếu câu ACTIVE so với số câu cần
     * phát; toàn bộ thay đổi rollback cùng nhau.
     */
    @Transactional
    public AssessmentViews.BulkResult bulkStatus(AssessmentRequests.BulkStatusRequest request) {
        List<Question> found = requireBulk(request.ids());
        Map<BankKey, Bank> banks = managedBanks(found);
        String actor = learning.actor().person().id();
        lock.acquire();

        List<Question> changed = found.stream().filter(q -> !request.status().equals(q.getStatus())).toList();
        changed.forEach(q -> {
            q.setStatus(request.status());
            repository.save(q, actor);
        });
        repository.flush();
        if (ItemStatus.INACTIVE.equals(request.status())) {
            boolean shortage = repository.publishedQuizzesUsing(request.ids()).stream()
                .anyMatch(z -> repository.activePoolSize(z.getId()) < z.getQuestionCount());
            if (shortage) {
                throw new BusinessException(AssessmentResponseCode.QUIZ_NOT_READY);
            }
        }
        auditByBank(actor, AuditAction.QUESTIONS_STATUS_CHANGED, changed, banks, request.status());
        return new AssessmentViews.BulkResult(changed.size());
    }

    /** Xóa mềm hàng loạt; câu còn nằm trong pool của đề chưa xóa chỉ được ngưng sử dụng. */
    @Transactional
    public AssessmentViews.BulkResult bulkDelete(AssessmentRequests.BulkIdsRequest request) {
        List<Question> found = requireBulk(request.ids());
        Map<BankKey, Bank> banks = managedBanks(found);
        String actor = learning.actor().person().id();
        lock.acquire();

        if (!repository.questionsInPools(request.ids()).isEmpty()) {
            throw BusinessException.field(AssessmentResponseCode.QUESTION_IN_USE, "ids", "IN_USE",
                "Có câu hỏi đang được dùng trong bài kiểm tra; chỉ có thể ngưng sử dụng.");
        }
        found.forEach(q -> {
            q.setDeleted(true);
            repository.save(q, actor);
        });
        repository.flush();
        auditByBank(actor, AuditAction.QUESTIONS_DELETED, found, banks, null);
        return new AssessmentViews.BulkResult(found.size());
    }

    /** Mọi ID phải tồn tại và chưa xóa; thiếu một ID thì từ chối cả lô để không cập nhật một phần. */
    private List<Question> requireBulk(List<Long> ids) {
        List<Long> distinct = ids.stream().distinct().toList();
        List<Question> found = repository.questions(distinct).stream()
            .filter(q -> !Boolean.TRUE.equals(q.getDeleted())).toList();
        if (found.size() != distinct.size()) {
            throw BusinessException.field(AssessmentResponseCode.INVALID_REFERENCE, "ids", "NOT_FOUND",
                "Có câu hỏi không tồn tại hoặc đã bị xóa.");
        }
        return found;
    }

    private record Bank(Long programId, Long courseId, Long ownerOrgId) {
    }

    private record BankKey(Long courseId, Long programId) {
        static BankKey of(Question question) {
            return question.getCourseId() != null ? new BankKey(question.getCourseId(), null)
                : new BankKey(null, question.getProgramId());
        }
    }

    private Bank courseBank(ManagedCourse course) {
        return new Bank(learning.courseProgramId(course.id()), course.id(), course.ownerOrgId());
    }

    private Bank questionBank(Question question, boolean programScope) {
        if (programScope || question.getCourseId() == null) {
            var program = learning.requireManagedProgram(question.getProgramId());
            return new Bank(program.id(), question.getCourseId(), program.ownerOrgId());
        }
        ManagedCourse course = learning.requireManagedCourse(question.getCourseId());
        return new Bank(question.getProgramId(), course.id(), course.ownerOrgId());
    }

    /** Kiểm quyền mọi ngân hàng trước khi ghi; dữ liệu courseId cũ vẫn giữ đúng scope quản lý khóa. */
    private Map<BankKey, Bank> managedBanks(List<Question> questions) {
        Map<BankKey, Bank> banks = new LinkedHashMap<>();
        for (Question question : questions) {
            banks.computeIfAbsent(BankKey.of(question), key -> questionBank(question, false));
        }
        return banks;
    }

    private void auditByBank(String actor, String action, List<Question> questions,
                             Map<BankKey, Bank> banks, String status) {
        questions.stream().collect(Collectors.groupingBy(BankKey::of)).forEach((key, group) -> {
            Map<String, Object> changes = new LinkedHashMap<>();
            changes.put("questionIds", group.stream().map(Question::getId).toList());
            if (status != null) {
                changes.put("status", status);
            }
            boolean legacy = key.courseId() != null;
            Long id = legacy ? key.courseId() : key.programId();
            audit.record(actor, action, legacy ? "course" : "training_program", id.toString(),
                banks.get(key).ownerOrgId(), changes);
        });
    }

    private AssessmentViews.QuestionDetail detail(Question question) {
        var options = repository.options(List.of(question.getId())).stream().map(mapper::view).toList();
        return new AssessmentViews.QuestionDetail(question.getId(), question.getCode(), question.getCourseId(),
            question.getLessonId(), question.getCompetencyId(), question.getQuestionType(), question.getContent(),
            question.getExplanation(), question.getDifficulty(), question.getPoints(), question.getStatus(),
            question.getContentVersion(), options, repository.quizzesUsing(question.getId()),
            question.getRowVersion(), question.getCreateTime(), question.getUpdateTime(), question.getProgramId());
    }

    private Question requireQuestion(Long id) {
        Question question = repository.require(Question.class, id);
        if (Boolean.TRUE.equals(question.getDeleted())) {
            throw new BusinessException(AssessmentResponseCode.NOT_FOUND);
        }
        return question;
    }

    private static void apply(Question question, AssessmentRequests.QuestionRequest request) {
        question.setLessonId(request.lessonId());
        question.setCompetencyId(request.competencyId());
        question.setQuestionType(request.questionType());
        question.setContent(request.content().trim());
        question.setExplanation(request.explanation());
        question.setDifficulty(request.difficulty());
        question.setPoints(request.points());
        question.setStatus(request.status());
    }

    /** Nhóm năng lực mới gắn phải ACTIVE; giữ nguyên nhóm cũ đã ngưng thì vẫn cho lưu để không chặn sửa câu. */
    private void validateReferences(Long courseId, AssessmentRequests.QuestionRequest request, Question current) {
        if (request.lessonId() != null
            && (current == null || !request.lessonId().equals(current.getLessonId()))) {
            if (courseId == null) {
                throw BusinessException.validation("lessonId", "INVALID_VALUE",
                    "Câu hỏi chương trình không gắn bài học của khóa.");
            }
            try {
                learning.requireLessonInCourse(request.lessonId(), courseId);
            } catch (BusinessException exception) {
                throw BusinessException.field(AssessmentResponseCode.INVALID_REFERENCE, "lessonId", "NOT_FOUND",
                    "Bài học không thuộc khóa học này.");
            }
        }
        boolean keepCurrent = current != null && request.competencyId().equals(current.getCompetencyId());
        Competency competency = repository.competencies(List.of(request.competencyId())).stream()
            .filter(c -> !Boolean.TRUE.equals(c.getDeleted())).findFirst().orElse(null);
        if (competency == null || (!keepCurrent && !ItemStatus.ACTIVE.equals(competency.getStatus()))) {
            throw BusinessException.field(AssessmentResponseCode.INVALID_REFERENCE, "competencyId", "NOT_FOUND",
                "Nhóm năng lực không tồn tại hoặc đã ngưng sử dụng.");
        }
    }

    /** SINGLE đúng 1 đáp án đúng; MULTIPLE ít nhất 2. ID phương án gửi lên phải thuộc chính câu hỏi này. */
    static List<AssessmentRequests.OptionInput> validateOptions(AssessmentRequests.QuestionRequest request,
                                                              List<QuestionOption> existing) {
        Set<Long> owned = existing.stream().map(QuestionOption::getId).collect(Collectors.toSet());
        Set<Long> seen = new HashSet<>();
        for (int i = 0; i < request.options().size(); i++) {
            Long optionId = request.options().get(i).id();
            if (optionId != null && (!owned.contains(optionId) || !seen.add(optionId))) {
                throw BusinessException.validation("options[" + i + "].id", "INVALID_VALUE",
                    "Phương án không thuộc câu hỏi này hoặc bị lặp.");
            }
        }
        long correct = request.options().stream().filter(AssessmentRequests.OptionInput::correct).count();
        if (QuestionType.SINGLE.equals(request.questionType()) && correct != 1) {
            throw BusinessException.validation("options", "INVALID_VALUE",
                "Câu một đáp án phải có đúng 1 đáp án đúng.");
        }
        if (QuestionType.MULTIPLE.equals(request.questionType()) && correct < 2) {
            throw BusinessException.validation("options", "INVALID_VALUE",
                "Câu nhiều đáp án cần ít nhất 2 đáp án đúng.");
        }
        return request.options();
    }

    private static boolean optionsChanged(List<QuestionOption> existing, List<AssessmentRequests.OptionInput> inputs) {
        if (existing.size() != inputs.size()) {
            return true;
        }
        Map<Long, QuestionOption> byId = existing.stream()
            .collect(Collectors.toMap(QuestionOption::getId, Function.identity()));
        return inputs.stream().anyMatch(input -> {
            QuestionOption option = input.id() == null ? null : byId.get(input.id());
            return option == null || !option.getContent().equals(input.content().trim())
                || option.getCorrect() != input.correct();
        });
    }

    private void mergeOptions(Long questionId, List<QuestionOption> existing,
                              List<AssessmentRequests.OptionInput> inputs) {
        Map<Long, QuestionOption> byId = existing.stream()
            .collect(Collectors.toMap(QuestionOption::getId, Function.identity()));
        List<Long> kept = new ArrayList<>();
        for (int i = 0; i < inputs.size(); i++) {
            AssessmentRequests.OptionInput input = inputs.get(i);
            QuestionOption option = input.id() == null ? new QuestionOption() : byId.get(input.id());
            option.setQuestionId(questionId);
            option.setContent(input.content().trim());
            option.setCorrect(input.correct());
            option.setSortNo(i);
            if (option.getId() == null) {
                repository.persist(option);
            } else {
                kept.add(option.getId());
            }
        }
        existing.stream().filter(o -> !kept.contains(o.getId())).forEach(o -> o.setDeleted(true));
    }

    static void requireCode(String field, String value, Set<String> allowed) {
        if (value != null && !allowed.contains(value)) {
            throw BusinessException.validation(field, "INVALID_VALUE", "Giá trị không hợp lệ.");
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static BusinessException duplicateCode() {
        return BusinessException.field(AssessmentResponseCode.DUPLICATE, "code", "ALREADY_EXISTS",
            "Mã câu hỏi đã tồn tại.");
    }
}

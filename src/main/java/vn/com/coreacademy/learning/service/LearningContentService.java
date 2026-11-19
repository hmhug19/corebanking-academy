package vn.com.coreacademy.learning.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.safety.Cleaner;
import org.jsoup.safety.Safelist;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.com.coreacademy.audit.service.IdentityAuditService;
import vn.com.coreacademy.common.exception.BusinessException;
import vn.com.coreacademy.identity.service.LearningIdentityService;
import vn.com.coreacademy.learning.constants.LearningCodes.AssetStatus;
import vn.com.coreacademy.learning.constants.LearningCodes.AssetUsage;
import vn.com.coreacademy.learning.constants.LearningCodes.AuditAction;
import vn.com.coreacademy.learning.constants.LearningCodes.BlockType;
import vn.com.coreacademy.learning.constants.LearningCodes.BodyFormat;
import vn.com.coreacademy.learning.constants.LearningCodes.CatalogStatus;
import vn.com.coreacademy.learning.constants.LearningCodes.CompletionMode;
import vn.com.coreacademy.learning.constants.LearningCodes.CourseStatus;
import vn.com.coreacademy.learning.constants.LearningCodes.LessonType;
import vn.com.coreacademy.learning.constants.LearningCodes.MediaMime;
import vn.com.coreacademy.learning.constants.LearningCodes.Requirement;
import vn.com.coreacademy.learning.constants.LearningCodes.RevisionState;
import vn.com.coreacademy.learning.constants.LearningCodes.ScormCompletion;
import vn.com.coreacademy.learning.constants.LearningLimits;
import vn.com.coreacademy.learning.dto.LearningDetails;
import vn.com.coreacademy.learning.dto.LearningRequests;
import vn.com.coreacademy.learning.dto.LearningViews;
import vn.com.coreacademy.learning.entity.BlockAsset;
import vn.com.coreacademy.learning.entity.BusinessModule;
import vn.com.coreacademy.learning.entity.Course;
import vn.com.coreacademy.learning.entity.CourseAssessment;
import vn.com.coreacademy.learning.entity.CourseChapter;
import vn.com.coreacademy.learning.entity.CourseCompletionRule;
import vn.com.coreacademy.learning.entity.CourseLesson;
import vn.com.coreacademy.learning.entity.CourseObjective;
import vn.com.coreacademy.learning.entity.CourseRevision;
import vn.com.coreacademy.learning.entity.LearningAsset;
import vn.com.coreacademy.learning.entity.LearningRecord;
import vn.com.coreacademy.learning.entity.LessonBlock;
import vn.com.coreacademy.learning.entity.ProcessStep;
import vn.com.coreacademy.learning.entity.TrainingField;
import vn.com.coreacademy.learning.entity.TrainingProgram;
import vn.com.coreacademy.learning.exception.LearningResponseCode;
import vn.com.coreacademy.learning.mapper.LearningMapper;
import vn.com.coreacademy.learning.mapper.LessonBlockJson;
import vn.com.coreacademy.learning.repository.LearningRepository;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class LearningContentService {
    private static final Cleaner LESSON_HTML = new Cleaner(new Safelist()
        .addTags("p", "div", "br", "h2", "h3", "h4", "ul", "ol", "li", "blockquote", "pre", "code",
            "b", "strong", "i", "em", "u", "s", "strike", "a", "table", "caption", "thead", "tbody",
            "tfoot", "tr", "th", "td", "img")
        .addAttributes("td", "colspan", "rowspan")
        .addAttributes("th", "colspan", "rowspan")
        .addAttributes("img", "data-asset-id", "alt")
        .addAttributes("a", "href")
        .addProtocols("a", "href", "https", "http"));

    private final LearningRepository repository;
    private final LearningAccessPolicy access;
    private final LearningIdentityService identity;
    private final LearningWriteLock writeLock;
    private final LearningMapper mapper;
    private final IdentityAuditService audit;
    private final List<LearningResourceExtension> resourceExtensions;
    private final LearningQuizDirectory quizzes;
    private final LearningScormDirectory scorm;
    private final LearningAiOwnership aiOwnership;

    /**
     * Tạo định danh khóa và bản nháp đầu tiên; không lấy owner/actor từ payload.
     * Nếu payload có {@code draft} thì ghi luôn nội dung nháp trong cùng transaction; {@code draft.title} ưu tiên hơn {@code title}.
     */
    @Transactional
    public LearningDetails.CourseDetail create(LearningRequests.CreateCourse request) {
        // Bước 1: xác định caller và kiểm tra scope tổ chức/nhóm trước khi tạo tài nguyên.
        var actor = access.actor();
        writeLock.acquire();
        Long ownerTeamId = request.ownerTeamId();
        if (ownerTeamId == null
            && Objects.equals(actor.person().orgId(), request.ownerOrgId())
            && !access.canManage(actor, request.ownerOrgId(), null, actor.person().id())) {
            ownerTeamId = actor.person().teamId();
        }
        access.requireManage(actor, request.ownerOrgId(), ownerTeamId, actor.person().id());
        if (ownerTeamId != null
            && !identity.activeTeam(ownerTeamId).orgId().equals(request.ownerOrgId())) {
            throw new BusinessException(LearningResponseCode.INVALID_REFERENCE);
        }

        // Bước 2: kiểm tra mã và INSERT dưới khóa đã lấy, giữ nguyên tử dù DB không có UNIQUE/FK.
        if (repository.courseCodeExists(request.code().strip())) {
            throw new BusinessException(LearningResponseCode.DUPLICATE);
        }
        Course course = new Course();
        course.setCode(request.code().strip());
        course.setOwnerOrgId(request.ownerOrgId());
        course.setOwnerTeamId(ownerTeamId);
        course.setOwnerUserId(actor.person().id());
        course.setLifecycleStatus(CourseStatus.ACTIVE);
        repository.save(course, actor.person().id());
        var draft = request.draft();
        String title = draft != null && draft.title() != null ? draft.title() : request.title();
        CourseRevision revision = newRevision(course, title, actor.person().id(), 1);
        if (draft != null) {
            // Cùng transaction với khóa mới: nội dung nháp lỗi thì rollback, không để lại khóa rỗng.
            applyDraft(course, revision, draft.withTitle(title), actor.person().id());
        }

        // Bước 3: lưu audit cùng transaction; flush để trả đúng rowVersion do JPA sinh.
        record(actor.person().id(), AuditAction.COURSE_CREATED, course);
        repository.flush();
        return detail(course, revision);
    }

    public LearningDetails.CourseDetail get(Long courseId, Long revisionId) {
        var actor = access.actor();
        Course course = repository.require(Course.class, courseId);
        access.requireManage(actor, course);
        return detail(course, selectRevision(course, revisionId));
    }

    /** PUT thay toàn bộ cây draft; ID chương/bài/khối nháp được cấp lại và thứ tự lấy từ mảng payload. */
    @Transactional
    public LearningDetails.CourseDetail saveDraft(Long courseId, Long revisionId,
                                                  LearningRequests.DraftRequest request) {
        // Bước 1: quyền được kiểm tra từ course thật, sau đó serialize ghi trước khi kiểm tra version.
        var actor = access.actor();
        Course course = repository.require(Course.class, courseId);
        access.requireManage(actor, course);
        writeLock.acquire();
        repository.refresh(course);
        CourseRevision revision = repository.require(CourseRevision.class, revisionId);
        requireDraft(course, revision);
        version(revision, request.expectedVersion());

        // Bước 2: kiểm tra và thay cây draft bằng nội dung mới; khóa do AI viết giữ trạng thái từng bài.
        var aiBefore = aiOwnership.snapshot(revision.getId());
        if (aiBefore != null) {
            aiOwnership.requireStructureAllowed(revision.getId(), aiBefore, request.content());
        }
        applyDraft(course, revision, request.content(), actor.person().id());
        if (aiBefore != null) {
            repository.flush();
            aiOwnership.reconcile(revision.getId(), aiBefore, request.content());
        }

        // Bước 3: audit và flush trong cùng transaction để caller nhận version mới dùng lần lưu sau.
        record(actor.person().id(), AuditAction.DRAFT_SAVED, course);
        repository.flush();
        repository.incrementVersion(revision);
        return detail(course, revision);
    }

    /** Gắn đề sau khi Assessment đã ghi cấu hình và pool, không ghi lại toàn bộ cây bài học lần thứ hai. */
    @Transactional
    public LearningDetails.CourseDetail attachFinalExam(Long courseId, Long revisionId, Long expectedVersion,
                                                       Long quizId) {
        var actor = access.actor();
        Course course = repository.require(Course.class, courseId);
        access.requireManage(actor, course);
        writeLock.acquire();
        CourseRevision revision = repository.require(CourseRevision.class, revisionId);
        requireDraft(course, revision);
        version(revision, expectedVersion);
        var quiz = validateFinalQuiz(course, quizId);
        CourseCompletionRule rule = repository.rules(revisionId).get(0);
        rule.setFinalQuizId(quizId);
        repository.save(rule, actor.person().id());
        writeFinalAssessment(revision, quiz.orElse(null), actor.person().id());
        repository.flush();
        repository.incrementVersion(revision);
        record(actor.person().id(), AuditAction.DRAFT_SAVED, course);
        return detail(course, revision);
    }

    /** Gắn đề vừa tạo vào bài học của cây nháp đã ghi trong cùng transaction. */
    @Transactional
    public LearningDetails.CourseDetail attachLessonQuiz(LearningDetails.CourseDetail saved, int chapterIndex,
                                                          int lessonIndex, Long quizId) {
        if (chapterIndex >= saved.chapters().size()
            || lessonIndex >= saved.chapters().get(chapterIndex).lessons().size()) {
            throw BusinessException.validation("lessonTarget", "INVALID_VALUE", "Không tìm thấy bài học trong bản nháp.");
        }
        var selected = saved.chapters().get(chapterIndex).lessons().get(lessonIndex).lesson();
        if (!LessonType.QUIZ.equals(selected.lessonType())) {
            throw BusinessException.validation("lessonTarget", "INVALID_VALUE", "Chọn bài học loại bài kiểm tra.");
        }
        var actor = access.actor();
        Course course = repository.require(Course.class, saved.course().id());
        access.requireManage(actor, course);
        writeLock.acquire();
        CourseRevision revision = repository.require(CourseRevision.class, saved.revision().id());
        requireDraft(course, revision);
        version(revision, saved.revision().rowVersion());
        var quiz = quizzes.find(quizId);
        if (quiz.isEmpty() || quiz.get().deleted() || !course.getId().equals(quiz.get().courseId())) {
            throw new BusinessException(LearningResponseCode.QUIZ_INVALID);
        }
        CourseLesson lesson = repository.require(CourseLesson.class, selected.id());
        lesson.setQuizId(quizId);
        repository.save(lesson, actor.person().id());
        repository.flush();
        repository.incrementVersion(revision);
        return detail(course, revision);
    }

    /**
     * Kiểm tra rồi ghi toàn bộ nội dung nháp vào revision; dùng chung cho lưu nháp và tạo khóa kèm nháp.
     * Lỗi bất kỳ ném ngoại lệ trước khi cây cũ bị thay, transaction của caller sẽ rollback.
     */
    void applyDraft(Course course, CourseRevision revision, LearningRequests.DraftContent draft,
                            String actor) {
        // Bước 1: kiểm tra danh mục và quy mô.
        validateCatalog(course, draft.programId(), draft.moduleId(), false);
        if (draft.programId() != null && !Objects.equals(draft.programId(), revision.getProgramId())) {
            access.requireUsableProgram(access.actor(), repository.require(TrainingProgram.class, draft.programId()));
        }
        validateDraftSize(draft);

        // Bước 2: kiểm tra tệp/quy tắc khối trước khi thay cây; lỗi bất kỳ rollback toàn bộ.
        var finalQuiz = validateFinalQuiz(course, draft.finalQuizId());
        if (draft.coverAssetId() != null) {
            LearningAsset cover = usableAsset(course, draft.coverAssetId());
            if (!cover.getMimeType().startsWith(MediaMime.IMAGE_PREFIX)) {
                throw new BusinessException(LearningResponseCode.ASSET_INVALID);
            }
        }
        for (var chapter : draft.chapters()) {
            for (var lesson : chapter.lessons()) {
                validateLessonInput(course, lesson);
                for (var block : lesson.blocks()) {
                    validateBlockInput(course, block);
                }
            }
        }

        // Bước 3: chỉ xóa cây draft, không đụng các revision published/enrollment/progress lịch sử.
        clearDraftTree(revision.getId());
        revision.setProgramId(draft.programId());
        revision.setModuleId(draft.moduleId());
        revision.setTitle(draft.title());
        revision.setSummary(draft.summary());
        revision.setLevel(draft.level());
        revision.setDefaultRequirement(draft.defaultRequirement());
        revision.setCoverAssetId(draft.coverAssetId());
        revision.setCoverIcon(draft.coverIcon());
        revision.setCoverColor(draft.coverColor());
        repository.save(revision, actor);
        writeTree(revision, draft, actor);
        CourseCompletionRule rule = repository.rules(revision.getId()).get(0);
        rule.setRewardPoints(draft.rewardPoints());
        rule.setFinalQuizId(draft.finalQuizId());
        repository.save(rule, actor);
        writeFinalAssessment(revision, finalQuiz.orElse(null), actor);
    }

    /**
     * Đề được chọn làm bài kiểm tra cuối khóa phải tồn tại, chưa xóa, thuộc chính khóa và là loại FINAL_EXAM.
     * Đề còn nháp vẫn được lưu; việc đề phải đã xuất bản chỉ bị chặn khi xuất bản khóa.
     */
    private Optional<LearningQuizDirectory.QuizRef> validateFinalQuiz(Course course, Long finalQuizId) {
        if (finalQuizId == null) {
            return Optional.empty();
        }
        var quiz = quizzes.find(finalQuizId);
        if (quiz.isEmpty() || quiz.get().deleted() || !course.getId().equals(quiz.get().courseId())
            || !quiz.get().finalExam()) {
            throw BusinessException.field(LearningResponseCode.QUIZ_INVALID, "finalQuizId", "INVALID_VALUE",
                LearningResponseCode.QUIZ_INVALID.getMessage());
        }
        return quiz;
    }

    /**
     * Thay các dòng bài kiểm tra của revision bằng tối đa một dòng cuối khóa. Điểm đạt, số lượt thi và cho thi lại
     * chỉ chụp từ đề tại thời điểm lưu để hiển thị; việc đạt hay không do chính đề quyết định.
     */
    private void writeFinalAssessment(CourseRevision revision, LearningQuizDirectory.QuizRef quiz, String actor) {
        repository.assessments(revision.getId()).forEach(repository::remove);
        if (quiz == null) {
            return;
        }
        repository.flush();
        CourseAssessment row = new CourseAssessment();
        row.setRevisionId(revision.getId());
        row.setAssessmentRevisionId(quiz.quizId());
        row.setPlacement(CourseAssessment.PLACEMENT_FINAL);
        row.setIsRequired(true);
        row.setPassScorePercent(quiz.passPercent());
        row.setMaxAttempts(quiz.maxAttempts());
        row.setAllowRetake(quiz.allowRetakeAfterPass());
        row.setSortNo(0);
        repository.save(row, actor);
    }

    @Transactional
    public LearningDetails.CourseDetail publish(Long courseId, Long revisionId,
                                                LearningRequests.VersionRequest request, boolean reviewConfirmed) {
        // Bước 1: chỉ người có quyền sửa khóa mới được publish; version cũ không được ghi đè.
        var actor = access.actor();
        Course course = repository.require(Course.class, courseId);
        access.requireManage(actor, course);
        writeLock.acquire();
        repository.refresh(course);
        CourseRevision revision = repository.require(CourseRevision.class, revisionId);
        requireDraft(course, revision);
        version(revision, request.expectedVersion());
        publishLessonQuizzes(course, revisionId);
        requirePublishable(course, revision);
        resourceExtensions.forEach(extension -> extension.validateBeforePublish(revisionId));
        // Nội dung do AI tạo chỉ xuất bản được khi người xuất bản xác nhận đã rà soát; ghi lại ai và khi nào.
        if (revision.isAiGenerated()) {
            if (!reviewConfirmed) {
                throw BusinessException.validation("reviewConfirmed", "REVIEW_REQUIRED",
                    "Bản này có nội dung do AI tạo; hãy xác nhận đã rà soát nội dung trước khi xuất bản.");
            }
            revision.setReviewConfirmedBy(actor.person().id());
            revision.setReviewConfirmedAt(Instant.now());
        }

        // Bước 2: đóng băng bản nội dung và chuyển con trỏ; enrollment cũ giữ revision_id cũ.
        publishLinkedScorm(revisionId, actor.person().id());
        revision.setState(RevisionState.PUBLISHED);
        revision.setPublishedAt(Instant.now());
        revision.setPublishedBy(actor.person().id());
        course.setPublishedRevisionId(revision.getId());
        repository.save(revision, actor.person().id());
        repository.save(course, actor.person().id());
        if (revision.isAiGenerated()) {
            audit.record(actor.person().id(), AuditAction.COURSE_PUBLISHED, "course", course.getId().toString(),
                course.getOwnerOrgId(), Map.of("revisionId", revision.getId(), "aiGenerated", true,
                    "reviewConfirmedBy", actor.person().id(), "reviewConfirmedAt",
                    revision.getReviewConfirmedAt().toString()));
        } else {
            record(actor.person().id(), AuditAction.COURSE_PUBLISHED, course);
        }
        repository.flush();
        return detail(course, revision);
    }

    @Transactional
    public LearningDetails.CourseDetail draft(Long courseId) {
        // Bước 1: trả draft đang có để retry không tạo hai bản nháp cho cùng khóa.
        var actor = access.actor();
        Course course = repository.require(Course.class, courseId);
        access.requireManage(actor, course);
        writeLock.acquire();
        repository.refresh(course);
        var revisions = repository.revisions(courseId);
        var existing = revisions.stream().filter(r -> RevisionState.DRAFT.equals(r.getState())).findFirst();
        if (existing.isPresent()) {
            return detail(course, existing.get());
        }

        // Bước 2: clone từ bản công bố bằng DTO nội dung; không clone người học hoặc tiến độ.
        CourseRevision source = selectRevision(course, course.getPublishedRevisionId());
        CourseRevision target = newRevision(course, source.getTitle(), actor.person().id(),
            revisions.stream().mapToInt(CourseRevision::getRevisionNo).max().orElse(0) + 1);
        cloneContent(course, source, target, actor.person().id(), true);
        record(actor.person().id(), AuditAction.DRAFT_CREATED, course);
        repository.flush();
        return detail(course, target);
    }

    @Transactional
    public LearningDetails.CourseDetail copy(Long courseId, LearningRequests.CopyRequest request) {
        var actor = access.actor();
        Course source = repository.require(Course.class, courseId);
        access.requireManage(actor, source);
        writeLock.acquire();
        repository.refresh(source);
        if (repository.courseCodeExists(request.code().strip())) {
            throw new BusinessException(LearningResponseCode.DUPLICATE);
        }

        // Tạo khóa mới thuộc cùng tổ chức; chỉ chia sẻ immutable assets, không copy assignment/share/enrollment.
        Course target = new Course();
        target.setCode(request.code().strip());
        target.setOwnerOrgId(source.getOwnerOrgId());
        target.setOwnerTeamId(source.getOwnerTeamId());
        target.setOwnerUserId(actor.person().id());
        target.setLifecycleStatus(CourseStatus.ACTIVE);
        target.setCopiedFromCourseId(source.getId());
        repository.save(target, actor.person().id());
        CourseRevision sourceRevision = selectRevision(source, null);
        CourseRevision revision = newRevision(target, sourceRevision.getTitle(), actor.person().id(), 1);
        cloneContent(target, sourceRevision, revision, actor.person().id(), false);
        record(actor.person().id(), AuditAction.COURSE_COPIED, target);
        repository.flush();
        return detail(target, revision);
    }

    /**
     * Sao chép khóa vào một chương trình mới trong cùng transaction của việc sao chép chương trình: bản nháp,
     * mã cấp sẵn, không copy assignment/share/enrollment. Người gọi đã giữ khóa ghi.
     */
    @Transactional
    public Course copyIntoProgram(Course source, Long programId, String code) {
        var actor = access.actor();
        access.requireManage(actor, source);
        Course target = new Course();
        target.setCode(code);
        target.setOwnerOrgId(source.getOwnerOrgId());
        target.setOwnerTeamId(source.getOwnerTeamId());
        target.setOwnerUserId(actor.person().id());
        target.setLifecycleStatus(CourseStatus.ACTIVE);
        target.setCopiedFromCourseId(source.getId());
        repository.save(target, actor.person().id());
        CourseRevision sourceRevision = selectRevision(source, null);
        CourseRevision revision = newRevision(target, sourceRevision.getTitle(), actor.person().id(), 1);
        cloneContent(target, sourceRevision, revision, actor.person().id(), false);
        revision.setProgramId(programId);
        repository.save(revision, actor.person().id());
        record(actor.person().id(), AuditAction.COURSE_COPIED, target);
        return target;
    }

    /** Mã còn trống dạng {@code <mã>-COPY}, {@code <mã>-COPY2}... trong giới hạn 50 ký tự. */
    public String freeCopyCode(String code, Predicate<String> taken) {
        for (int n = 1; ; n++) {
            String suffix = n == 1 ? "-COPY" : "-COPY" + n;
            String candidate = code.substring(0, Math.min(code.length(), 50 - suffix.length())) + suffix;
            if (!taken.test(candidate)) {
                return candidate;
            }
        }
    }

    @Transactional
    public LearningViews.CourseView state(Long courseId, LearningRequests.CourseState request) {
        var actor = access.actor();
        Course course = repository.require(Course.class, courseId);
        access.requireManage(actor, course);
        writeLock.acquire();
        repository.refresh(course);
        version(course, request.expectedVersion());
        course.setLifecycleStatus(request.status());
        repository.save(course, actor.person().id());
        record(actor.person().id(), AuditAction.COURSE_STATE_CHANGED, course);
        repository.flush();
        return mapper.view(course);
    }

    /** Đọc cây theo lô, không truy vấn từng chương/bài/khối khi dựng DTO. Bên gọi phải kiểm tra quyền trước. */
    public LearningDetails.CourseDetail detail(Course course, CourseRevision revision) {
        if (!course.getId().equals(revision.getCourseId())) {
            throw new BusinessException(LearningResponseCode.INVALID_REFERENCE);
        }
        var chapters = repository.chapters(revision.getId());
        var lessons = repository.lessons(revision.getId());
        var blocks = repository.blocks(revision.getId());
        var links = repository.assetLinks(revision.getId());
        var steps = repository.steps(revision.getId());
        var assets = repository.assets(links.stream().map(BlockAsset::getAssetId).distinct().toList()).stream()
            .collect(Collectors.toMap(LearningAsset::getId, Function.identity()));
        var linksByBlock = links.stream().collect(Collectors.groupingBy(BlockAsset::getBlockId));
        var stepsByBlock = steps.stream().collect(Collectors.groupingBy(ProcessStep::getBlockId));
        Map<Long, List<LearningDetails.BlockDetail>> blocksByLesson = new HashMap<>();
        for (LessonBlock block : blocks) {
            var details = linksByBlock.getOrDefault(block.getId(), List.of()).stream()
                .map(link -> new LearningDetails.AssetDetail(mapper.view(link),
                    mapper.view(assets.get(link.getAssetId())))).toList();
            var blockSteps = stepsByBlock.getOrDefault(block.getId(), List.of()).stream().map(mapper::view).toList();
            blocksByLesson.computeIfAbsent(block.getLessonId(), key -> new ArrayList<>())
                .add(new LearningDetails.BlockDetail(mapper.view(block), details, blockSteps));
        }
        var lessonsByChapter = lessons.stream().collect(Collectors.groupingBy(CourseLesson::getChapterId));
        var tree = chapters.stream().map(chapter -> new LearningDetails.ChapterDetail(mapper.view(chapter),
            lessonsByChapter.getOrDefault(chapter.getId(), List.of()).stream()
                .map(lesson -> new LearningDetails.LessonDetail(mapper.view(lesson),
                    blocksByLesson.getOrDefault(lesson.getId(), List.of()))).toList())).toList();
        var rules = repository.rules(revision.getId());
        return new LearningDetails.CourseDetail(mapper.view(course), mapper.view(revision),
            repository.objectives(revision.getId()).stream().map(mapper::view).toList(), tree,
            rules.isEmpty() ? null : mapper.view(rules.get(0)),
            repository.finalAssessment(revision.getId()).map(CourseAssessment::getAssessmentRevisionId)
                .orElse(rules.isEmpty() ? null : rules.get(0).getFinalQuizId()));
    }

    public CourseRevision selectRevision(Course course, Long revisionId) {
        if (revisionId != null) {
            CourseRevision revision = repository.require(CourseRevision.class, revisionId);
            if (!course.getId().equals(revision.getCourseId())) {
                throw new BusinessException(LearningResponseCode.INVALID_REFERENCE);
            }
            return revision;
        }
        return repository.revisions(course.getId()).stream().findFirst()
            .orElseThrow(() -> new BusinessException(LearningResponseCode.NOT_FOUND));
    }

    public static void version(LearningRecord value, Long expected) {
        if (!Objects.equals(value.getRowVersion(), expected)) {
            throw new BusinessException(LearningResponseCode.CONFLICT);
        }
    }

    public void requireCatalogAvailable(CourseRevision revision) {
        validateCatalog(repository.require(Course.class, revision.getCourseId()),
            revision.getProgramId(), revision.getModuleId(), true);
    }

    void requireDraft(Course course, CourseRevision revision) {
        if (!course.getId().equals(revision.getCourseId()) || !RevisionState.DRAFT.equals(revision.getState())
            || CourseStatus.ARCHIVED.equals(course.getLifecycleStatus())) {
            throw new BusinessException(LearningResponseCode.CONFLICT);
        }
    }

    private CourseRevision newRevision(Course course, String title, String actor, int number) {
        CourseRevision revision = new CourseRevision();
        revision.setCourseId(course.getId());
        revision.setRevisionNo(number);
        revision.setTitle(title);
        revision.setState(RevisionState.DRAFT);
        revision.setDefaultRequirement(Requirement.MANDATORY);
        repository.save(revision, actor);
        CourseCompletionRule rule = new CourseCompletionRule();
        rule.setRevisionId(revision.getId());
        rule.setRequireAllRequiredLessons(true);
        rule.setRewardPoints(0);
        repository.save(rule, actor);
        return revision;
    }

    private void validateCatalog(Course course, Long programId, Long moduleId, boolean published) {
        if (programId == null) {
            if (published || moduleId != null) {
                throw new BusinessException(LearningResponseCode.INVALID_REFERENCE);
            }
            return;
        }
        TrainingProgram program = repository.require(TrainingProgram.class, programId);
        TrainingField field = repository.require(TrainingField.class, program.getFieldId());
        if (!program.getOwnerOrgId().equals(course.getOwnerOrgId())
            || !field.getOwnerOrgId().equals(course.getOwnerOrgId())) {
            throw new BusinessException(LearningResponseCode.INVALID_REFERENCE);
        }
        if (published && (!CatalogStatus.PUBLISHED.equals(program.getStatus())
            || !CatalogStatus.PUBLISHED.equals(field.getStatus()))) {
            throw new BusinessException(LearningResponseCode.CATALOG_UNAVAILABLE);
        }
        if (moduleId != null) {
            BusinessModule module = repository.require(BusinessModule.class, moduleId);
            if (!module.getFieldId().equals(program.getFieldId())) {
                throw new BusinessException(LearningResponseCode.INVALID_REFERENCE);
            }
            if (published && !CatalogStatus.PUBLISHED.equals(module.getStatus())) {
                throw new BusinessException(LearningResponseCode.CATALOG_UNAVAILABLE);
            }
        }
    }

    private void validateDraftSize(LearningRequests.DraftContent request) {
        long lessons = request.chapters().stream().mapToLong(c -> c.lessons().size()).sum();
        long blocks = request.chapters().stream().flatMap(c -> c.lessons().stream())
            .mapToLong(l -> l.blocks().size()).sum();
        if (lessons > LearningLimits.MAX_LESSONS_PER_COURSE
            || blocks > LearningLimits.MAX_BLOCKS_PER_COURSE) {
            throw BusinessException.validation("chapters", "OUT_OF_RANGE",
                "Tối đa %d bài và %d khối mỗi khóa.".formatted(
                    LearningLimits.MAX_LESSONS_PER_COURSE, LearningLimits.MAX_BLOCKS_PER_COURSE));
        }
    }

    /**
     * Bài nội dung không có quizId; bài kiểm tra không có khối và (nếu đã chọn) trỏ tới đề chưa xóa của chính khóa này.
     * Bản nháp được phép chưa chọn đề: việc đó chỉ bị chặn khi xuất bản.
     */
    private void validateLessonInput(Course course, LearningRequests.LessonInput lesson) {
        if (LessonType.SCORM.equals(lesson.lessonType())) {
            if (lesson.quizId() != null || !lesson.blocks().isEmpty()) {
                throw BusinessException.validation("chapters", "INVALID_VALUE",
                    "Bài SCORM không chứa đề thi hoặc khối nội dung khác.");
            }
            scorm.validate(lesson.scormPackageId(), course.getOwnerOrgId(), false);
            return;
        }
        if (lesson.scormPackageId() != null) {
            throw BusinessException.validation("scormPackageId", "INVALID_VALUE",
                "Chỉ bài SCORM được gắn bài giảng SCORM.");
        }
        if (lesson.scormCompletionRule() != null) {
            throw BusinessException.validation("scormCompletionRule", "INVALID_VALUE",
                "Chỉ bài SCORM được cấu hình điều kiện hoàn thành SCORM.");
        }
        if (LessonType.CONTENT.equals(lesson.lessonType())) {
            if (lesson.quizId() != null) {
                throw new BusinessException(LearningResponseCode.QUIZ_INVALID);
            }
            return;
        }
        if (!lesson.blocks().isEmpty()) {
            throw BusinessException.validation("chapters", "INVALID_VALUE",
                "Bài học loại kiểm tra không có khối nội dung.");
        }
        if (lesson.quizId() != null) {
            var quiz = quizzes.find(lesson.quizId());
            if (quiz.isEmpty() || quiz.get().deleted() || !course.getId().equals(quiz.get().courseId())) {
                throw new BusinessException(LearningResponseCode.QUIZ_INVALID);
            }
        }
    }

    /** Xuất bản gói nháp do tác giả được quyền quản lý cùng transaction xuất bản khóa học. */
    private void publishLinkedScorm(Long revisionId, String actor) {
        for (var lesson : repository.lessons(revisionId)) {
            if (LessonType.SCORM.equals(lesson.getLessonType())) {
                scorm.publish(lesson.getScormPackageId(), revisionId, actor);
            }
        }
    }

    private void validateBlockInput(Course course, LearningRequests.BlockInput block) {
        if ((CompletionMode.VIDEO_COVERAGE.equals(block.completionMode()) && !BlockType.VIDEO.equals(block.blockType()))
            || (CompletionMode.PDF_END.equals(block.completionMode()) && !BlockType.PDF.equals(block.blockType()))
            || (!BlockType.PROCESS.equals(block.blockType()) && !block.steps().isEmpty())
            || (BlockType.VIDEO.equals(block.blockType())
                && !CompletionMode.VIDEO_COVERAGE.equals(block.completionMode()))) {
            throw BusinessException.validation("chapters", "INVALID_VALUE",
                "Loại khối và cách hoàn thành chưa phù hợp.");
        }
        if (block.allowSeeking() && !BlockType.VIDEO.equals(block.blockType())) {
            throw BusinessException.validation("allowSeeking", "INVALID_VALUE",
                "Chỉ khối video mới được cho phép tua.");
        }
        if (CompletionMode.VIDEO_COVERAGE.equals(block.completionMode()) && block.completionThreshold() == null) {
            throw BusinessException.validation("completionThreshold", "REQUIRED", "Vui lòng đặt tỷ lệ xem video.");
        }
        validatePlayerLists(course, block);
        if (BlockType.TEXT.equals(block.blockType()) && BodyFormat.HTML.equals(block.bodyFormat()) && block.body() != null) {
            Document html = Jsoup.parseBodyFragment(block.body());
            Set<Long> attachedImages = block.assets().stream().filter(asset -> AssetUsage.IMAGE.equals(asset.usage()))
                .map(LearningRequests.AssetUse::assetId).collect(Collectors.toSet());
            for (var image : html.select("img")) {
                String id = image.attr("data-asset-id");
                if (id.matches("[1-9][0-9]{0,17}") && !attachedImages.contains(Long.valueOf(id))) {
                    throw new BusinessException(LearningResponseCode.ASSET_INVALID);
                }
            }
        }
        for (var link : block.assets()) {
            LearningAsset asset = usableAsset(course, link.assetId());
            boolean matches = switch (block.blockType()) {
                case BlockType.VIDEO -> AssetUsage.POSTER.equals(link.usage()) ?
                    asset.getMimeType().startsWith(MediaMime.IMAGE_PREFIX)
                    : AssetUsage.PRIMARY.equals(link.usage()) && asset.getMimeType().startsWith(MediaMime.VIDEO_PREFIX);
                case BlockType.PDF -> AssetUsage.PRIMARY.equals(link.usage()) &&
                    MediaType.APPLICATION_PDF_VALUE.equals(asset.getMimeType());
                case BlockType.IMAGE -> Set.of(AssetUsage.IMAGE, AssetUsage.PRIMARY).contains(link.usage())
                    && asset.getMimeType().startsWith(MediaMime.IMAGE_PREFIX);
                case BlockType.ATTACHMENT -> AssetUsage.ATTACHMENT.equals(link.usage()) &&
                    asset.getSizeBytes() <= LearningLimits.MAX_ATTACHMENT_BYTES
                    && (MediaType.APPLICATION_PDF_VALUE.equals(asset.getMimeType())
                        || asset.getMimeType().startsWith(MediaMime.OFFICE_PREFIX));
                case BlockType.PROCESS -> Set.of(AssetUsage.ATTACHMENT, AssetUsage.IMAGE).contains(link.usage());
                case BlockType.TEXT -> AssetUsage.IMAGE.equals(link.usage())
                    && Set.of(MediaType.IMAGE_PNG_VALUE, MediaType.IMAGE_JPEG_VALUE, MediaMime.IMAGE_WEBP)
                        .contains(asset.getMimeType());
                default -> false;
            };
            if (!matches) {
                throw new BusinessException(LearningResponseCode.ASSET_INVALID);
            }
        }
    }

    /**
     * Mốc và transcript chỉ dành cho khối video: tối đa 50 mốc (tên 1-120 ký tự) và 2000 đoạn (nội dung 1-1000 ký tự).
     * Mốc luôn có startSeconds không âm, tăng ngặt và không vượt thời lượng tài nguyên chính khi đã biết. Transcript
     * hoặc đều có startSeconds với cùng luật đó, hoặc đều để null (không thời điểm); trộn hai loại bị từ chối.
     */
    private void validatePlayerLists(Course course, LearningRequests.BlockInput block) {
        var chapters = block.videoChapters() == null ? List.<LearningRequests.VideoChapterInput>of()
            : block.videoChapters();
        var lines = block.transcript() == null ? List.<LearningRequests.TranscriptLineInput>of() : block.transcript();
        if (chapters.isEmpty() && lines.isEmpty()) {
            return;
        }
        if (!BlockType.VIDEO.equals(block.blockType())) {
            throw BusinessException.validation(chapters.isEmpty() ? "transcript" : "videoChapters", "INVALID_VALUE",
                "Mốc và transcript chỉ dùng cho khối video.");
        }
        BigDecimal duration = block.assets().stream().filter(a -> AssetUsage.PRIMARY.equals(a.usage())).findFirst()
            .map(a -> repository.require(LearningAsset.class, a.assetId()).getDurationSeconds()).orElse(null);
        checkPlayerList("videoChapters", "mốc", LearningLimits.MAX_VIDEO_CHAPTERS,
            LearningLimits.MAX_CHAPTER_TITLE_LENGTH, duration, false, chapters.stream()
                .map(c -> c == null ? null : new PlayerItem(c.startSeconds(), c.title())).toList());
        checkPlayerList("transcript", "đoạn transcript", LearningLimits.MAX_TRANSCRIPT_LINES,
            LearningLimits.MAX_TRANSCRIPT_TEXT_LENGTH, duration, true, lines.stream()
                .map(t -> t == null ? null : new PlayerItem(t.startSeconds(), t.text())).toList());
    }

    private record PlayerItem(BigDecimal startSeconds, String label) {
    }

    private void checkPlayerList(String field, String noun, int maxCount, int maxLength, BigDecimal duration,
                                 boolean untimedAllowed, List<PlayerItem> items) {
        if (items.size() > maxCount) {
            throw BusinessException.validation(field, "TOO_MANY", "Tối đa " + maxCount + " " + noun + ".");
        }
        long untimed = items.stream().filter(i -> i != null && i.startSeconds() == null).count();
        if (untimedAllowed && untimed > 0 && untimed < items.size()) {
            throw BusinessException.validation(field, "INVALID_VALUE",
                "Các " + noun + " phải đều có thời điểm hoặc đều không có.");
        }
        boolean allUntimed = untimedAllowed && untimed > 0;
        BigDecimal previous = null;
        for (PlayerItem item : items) {
            String label = item == null || item.label() == null ? "" : item.label().strip();
            if (label.isEmpty() || label.length() > maxLength) {
                throw BusinessException.validation(field, "INVALID_VALUE",
                    "Nội dung của " + noun + " phải từ 1 đến " + maxLength + " ký tự.");
            }
            if (allUntimed) {
                continue;
            }
            BigDecimal start = item.startSeconds();
            if (start == null || start.signum() < 0) {
                throw BusinessException.validation(field, "INVALID_VALUE",
                    "Thời điểm của " + noun + " phải là số giây không âm.");
            }
            if (previous != null && start.compareTo(previous) <= 0) {
                throw BusinessException.validation(field, "INVALID_VALUE",
                    "Thời điểm của " + noun + " phải tăng dần.");
            }
            if (duration != null && start.compareTo(duration) > 0) {
                throw BusinessException.validation(field, "INVALID_VALUE",
                    "Thời điểm của " + noun + " vượt quá thời lượng video.");
            }
            previous = start;
        }
    }

    private LearningAsset usableAsset(Course course, Long id) {
        LearningAsset asset = repository.require(LearningAsset.class, id);
        if (!course.getOwnerOrgId().equals(asset.getOwnerOrgId()) || !AssetStatus.READY.equals(asset.getStatus())
            || Boolean.TRUE.equals(asset.getIsDeleted())) {
            throw new BusinessException(LearningResponseCode.ASSET_INVALID);
        }
        return asset;
    }

    private void publishLessonQuizzes(Course course, Long revisionId) {
        repository.lessons(revisionId).stream()
            .filter(lesson -> LessonType.QUIZ.equals(lesson.getLessonType()) && lesson.getQuizId() != null)
            .map(CourseLesson::getQuizId)
            .distinct()
            .forEach(quizId -> quizzes.publishDraftLessonQuiz(course.getId(), quizId));
    }

    private void requirePublishable(Course course, CourseRevision revision) {
        if (!CourseStatus.ACTIVE.equals(course.getLifecycleStatus()) || revision.getTitle() == null
            || revision.getTitle().isBlank() || revision.getLevel() == null) {
            throw new BusinessException(LearningResponseCode.NOT_PUBLISHABLE);
        }
        validateCatalog(course, revision.getProgramId(), revision.getModuleId(), true);
        var detail = detail(course, revision);
        long requiredLessons = 0;
        if (detail.chapters().isEmpty()) {
            throw new BusinessException(LearningResponseCode.NOT_PUBLISHABLE);
        }
        for (var chapter : detail.chapters()) {
            if (chapter.lessons().isEmpty()) {
                throw new BusinessException(LearningResponseCode.NOT_PUBLISHABLE);
            }
            for (var lesson : chapter.lessons()) {
                if (lesson.lesson().isRequired()) {
                    requiredLessons++;
                }
                if (LessonType.SCORM.equals(lesson.lesson().lessonType())) {
                    scorm.validate(lesson.lesson().scormPackageId(), course.getOwnerOrgId(), true);
                    continue;
                }
                if (LessonType.QUIZ.equals(lesson.lesson().lessonType())) {
                    Optional<LearningQuizDirectory.QuizRef> quiz = lesson.lesson().quizId() == null
                        ? Optional.empty() : quizzes.find(lesson.lesson().quizId());
                    if (quiz.isEmpty() || !quiz.get().published() || !course.getId().equals(quiz.get().courseId())) {
                        throw new BusinessException(LearningResponseCode.QUIZ_LESSON_UNLINKED);
                    }
                    continue;
                }
                if (lesson.blocks().isEmpty() || lesson.blocks().stream().noneMatch(b -> b.block().isRequired())) {
                    throw new BusinessException(LearningResponseCode.NOT_PUBLISHABLE);
                }
                for (var block : lesson.blocks()) {
                    validatePublishedBlock(block);
                }
            }
        }
        if (requiredLessons == 0) {
            throw new BusinessException(LearningResponseCode.NOT_PUBLISHABLE);
        }
        if (revision.getCoverAssetId() != null) {
            usableAsset(course, revision.getCoverAssetId());
        }
        // Bài thi cuối khóa (nếu có) phải là đề FINAL_EXAM đã xuất bản, chưa xóa, của chính khóa này.
        if (detail.finalQuizId() != null) {
            var quiz = quizzes.find(detail.finalQuizId());
            if (quiz.isEmpty() || !quiz.get().published() || !quiz.get().finalExam()
                || !course.getId().equals(quiz.get().courseId())) {
                throw new BusinessException(LearningResponseCode.FINAL_EXAM_UNPUBLISHED);
            }
        }
    }

    private void validatePublishedBlock(LearningDetails.BlockDetail detail) {
        var block = detail.block();
        boolean valid = switch (block.blockType()) {
            case BlockType.TEXT -> block.body() != null && !block.body().isBlank() && block.bodyFormat() != null;
            case BlockType.PROCESS -> !detail.steps().isEmpty();
            case BlockType.VIDEO, BlockType.PDF -> detail.assets().stream()
                .filter(a -> AssetUsage.PRIMARY.equals(a.usage().usage())).count() == 1;
            case BlockType.IMAGE, BlockType.ATTACHMENT -> !detail.assets().isEmpty();
            default -> false;
        };
        if (!valid || detail.assets().stream()
            .anyMatch(a -> a.asset() == null || !AssetStatus.READY.equals(a.asset().status()))) {
            throw new BusinessException(LearningResponseCode.NOT_PUBLISHABLE);
        }
    }

    private void clearDraftTree(Long revisionId) {
        repository.steps(revisionId).forEach(repository::remove);
        repository.assetLinks(revisionId).forEach(repository::remove);
        repository.blocks(revisionId).forEach(repository::remove);
        repository.lessons(revisionId).forEach(repository::remove);
        repository.chapters(revisionId).forEach(repository::remove);
        repository.objectives(revisionId).forEach(repository::remove);
    }

    private void writeTree(CourseRevision revision, LearningRequests.DraftContent request, String actor) {
        for (int i = 0; i < request.objectives().size(); i++) {
            CourseObjective objective = new CourseObjective();
            objective.setRevisionId(revision.getId());
            objective.setDescription(request.objectives().get(i));
            objective.setSortNo(i);
            repository.save(objective, actor);
        }
        for (int ci = 0; ci < request.chapters().size(); ci++) {
            var chapterInput = request.chapters().get(ci);
            CourseChapter chapter = new CourseChapter();
            chapter.setRevisionId(revision.getId());
            chapter.setTitle(chapterInput.title());
            chapter.setSortNo(ci);
            repository.save(chapter, actor);
            for (int li = 0; li < chapterInput.lessons().size(); li++) {
                var lessonInput = chapterInput.lessons().get(li);
                CourseLesson lesson = new CourseLesson();
                lesson.setChapterId(chapter.getId());
                lesson.setTitle(lessonInput.title());
                lesson.setLessonType(lessonInput.lessonType());
                lesson.setEstimatedDurationSeconds(lessonInput.estimatedDurationSeconds());
                lesson.setIsRequired(lessonInput.isRequired());
                lesson.setSortNo(li);
                lesson.setQuizId(lessonInput.quizId());
                lesson.setScormPackageId(lessonInput.scormPackageId());
                lesson.setScormCompletionRule(LessonType.SCORM.equals(lessonInput.lessonType())
                    ? (lessonInput.scormCompletionRule() == null
                        ? ScormCompletion.COMPLETED_OR_PASSED
                        : lessonInput.scormCompletionRule()) : null);
                repository.save(lesson, actor);
                for (int bi = 0; bi < lessonInput.blocks().size(); bi++) {
                    writeBlock(lesson.getId(), lessonInput.blocks().get(bi), bi, actor);
                }
            }
        }
    }

    void writeBlock(Long lessonId, LearningRequests.BlockInput input, int sort, String actor) {
        LessonBlock block = new LessonBlock();
        block.setLessonId(lessonId);
        block.setBlockType(input.blockType());
        block.setTitle(input.title());
        block.setBody(BodyFormat.HTML.equals(input.bodyFormat()) ? cleanLessonHtml(input.body()) : input.body());
        block.setBodyFormat(input.bodyFormat());
        block.setCompletionMode(input.completionMode());
        block.setCompletionThreshold(input.completionThreshold());
        block.setIsRequired(input.isRequired());
        block.setSortNo(sort);
        block.setVideoChapters(LessonBlockJson.chaptersColumn(input.videoChapters()));
        block.setTranscript(LessonBlockJson.linesColumn(input.transcript()));
        block.setAllowSeeking(input.allowSeeking());
        block.setMediaAssetId(input.assets().stream().filter(a -> AssetUsage.PRIMARY.equals(a.usage()))
            .map(LearningRequests.AssetUse::assetId).findFirst().orElse(null));
        repository.save(block, actor);
        for (int i = 0; i < input.assets().size(); i++) {
            var use = input.assets().get(i);
            BlockAsset link = new BlockAsset();
            link.setBlockId(block.getId());
            link.setAssetId(use.assetId());
            link.setUsage(use.usage());
            link.setCaption(use.caption());
            link.setAltText(use.altText());
            link.setSortNo(i);
            repository.save(link, actor);
        }
        for (int i = 0; i < input.steps().size(); i++) {
            var inputStep = input.steps().get(i);
            ProcessStep step = new ProcessStep();
            step.setBlockId(block.getId());
            step.setTitle(inputStep.title());
            step.setDescription(inputStep.description());
            step.setSortNo(i);
            repository.save(step, actor);
        }
    }

    private String cleanLessonHtml(String source) {
        if (source == null || source.isBlank()) {
            return null;
        }
        Document parsed = Jsoup.parseBodyFragment(source);
        parsed.select("img").stream().filter(image -> !image.attr("data-asset-id").matches("[1-9][0-9]{0,17}"))
            .forEach(org.jsoup.nodes.Element::remove);
        Document cleaned = LESSON_HTML.clean(parsed);
        for (var cell : cleaned.select("td,th")) {
            for (String span : List.of("colspan", "rowspan")) {
                String value = cell.attr(span);
                if (!value.matches("[1-9][0-9]?|100")) cell.removeAttr(span);
            }
        }
        cleaned.outputSettings().prettyPrint(false);
        return cleaned.body().hasText() || !cleaned.select("img,table").isEmpty() ? cleaned.body().html() : null;
    }

    private void cloneContent(Course course, CourseRevision source, CourseRevision target, String actor,
                              boolean keepQuizLinks) {
        var original = detail(repository.require(Course.class, source.getCourseId()), source);
        target.setBasedOnRevisionId(source.getId());
        // A draft cloned from AI-generated content is still AI-generated: publishing it asks for the review again.
        target.setAiGenerated(source.isAiGenerated());
        target.setProgramId(source.getProgramId());
        target.setModuleId(source.getModuleId());
        target.setSummary(source.getSummary());
        target.setLevel(source.getLevel());
        target.setDefaultRequirement(source.getDefaultRequirement());
        target.setCoverAssetId(source.getCoverAssetId());
        target.setCoverIcon(source.getCoverIcon());
        target.setCoverColor(source.getCoverColor());
        repository.save(target, actor);
        var chapters = original.chapters().stream().map(c -> new LearningRequests.ChapterInput(c.chapter().title(),
            c.lessons().stream().map(l -> new LearningRequests.LessonInput(l.lesson().title(), l.lesson().lessonType(),
                l.lesson().estimatedDurationSeconds(), l.lesson().isRequired(),
                keepQuizLinks ? l.lesson().quizId() : null, l.blocks().stream()
                    .map(b -> new LearningRequests.BlockInput(b.block().blockType(), b.block().title(),
                        b.block().body(), b.block().bodyFormat(), b.block().completionMode(),
                        b.block().completionThreshold(), b.block().isRequired(), b.assets().stream()
                            .map(a -> new LearningRequests.AssetUse(a.usage().assetId(), a.usage().usage(),
                                a.usage().caption(), a.usage().altText())).toList(), b.steps().stream()
                            .map(s -> new LearningRequests.StepInput(s.title(), s.description())).toList(),
                        b.block().videoChapters().stream().map(ch -> new LearningRequests.VideoChapterInput(
                            ch.startSeconds(), ch.title())).toList(), b.block().transcript().stream()
                            .map(t -> new LearningRequests.TranscriptLineInput(t.startSeconds(), t.text())).toList(),
                        b.block().allowSeeking()))
                    .toList(), l.lesson().scormPackageId(), l.lesson().scormCompletionRule()))
                .toList())).toList();
        var payload = new LearningRequests.DraftContent(source.getProgramId(), source.getModuleId(),
            source.getTitle(), source.getSummary(), source.getLevel(), source.getDefaultRequirement(),
            source.getCoverAssetId(), source.getCoverIcon(), source.getCoverColor(),
            original.objectives().stream().map(LearningViews.CourseObjectiveView::description).toList(), chapters,
            original.completionRule().rewardPoints(), keepQuizLinks ? original.finalQuizId() : null);
        writeTree(target, payload, actor);
        repository.flush();
        var sourceLessons = repository.lessons(source.getId());
        var targetLessons = repository.lessons(target.getId());
        Map<Long, Long> lessonIds = new HashMap<>();
        boolean sameCourse = source.getCourseId().equals(target.getCourseId());
        for (int index = 0; index < sourceLessons.size(); index++) {
            var sourceLesson = sourceLessons.get(index);
            var targetLesson = targetLessons.get(index);
            lessonIds.put(sourceLesson.getId(), targetLesson.getId());
            if (sameCourse) {
                // Trỏ tới bài gốc đầu tiên, không phải bản sao liền trước (xem CourseLesson.sourceLessonId).
                targetLesson.setSourceLessonId(sourceLesson.getSourceLessonId() != null
                    ? sourceLesson.getSourceLessonId() : sourceLesson.getId());
                repository.save(targetLesson, actor);
            }
        }
        resourceExtensions.forEach(extension -> extension.cloneResources(source.getId(), target.getId(), lessonIds));
        var rule = repository.rules(target.getId()).get(0);
        rule.setRewardPoints(payload.rewardPoints());
        rule.setFinalQuizId(keepQuizLinks ? original.finalQuizId() : null);
        repository.save(rule, actor);
        writeFinalAssessment(target, payload.finalQuizId() == null ? null
            : quizzes.find(payload.finalQuizId()).orElse(null), actor);
    }

    private void record(String actor, String action, Course course) {
        audit.record(actor, action, "course", course.getId().toString(), course.getOwnerOrgId(), Map.of());
    }
}

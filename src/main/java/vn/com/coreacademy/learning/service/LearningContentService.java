package vn.com.coreacademy.learning.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
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
import vn.com.coreacademy.learning.constants.LearningLimits;
import vn.com.coreacademy.learning.dto.LearningDetails;
import vn.com.coreacademy.learning.dto.LearningRequests;
import vn.com.coreacademy.learning.dto.LearningViews;
import vn.com.coreacademy.learning.entity.BlockAsset;
import vn.com.coreacademy.learning.entity.BusinessModule;
import vn.com.coreacademy.learning.entity.Course;
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
import vn.com.coreacademy.learning.repository.LearningRepository;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class LearningContentService {
    private final LearningRepository repository;
    private final LearningAccessPolicy access;
    private final LearningIdentityService identity;
    private final LearningWriteLock writeLock;
    private final LearningMapper mapper;
    private final IdentityAuditService audit;

    /** Tạo định danh khóa và bản nháp đầu tiên; không lấy owner/actor từ payload. */
    @Transactional
    public LearningDetails.CourseDetail create(LearningRequests.CreateCourse request) {
        // Bước 1: xác định caller và kiểm tra scope tổ chức/nhóm trước khi tạo tài nguyên.
        var actor = access.actor();
        writeLock.acquire();
        access.requireManage(actor, request.ownerOrgId(), request.ownerTeamId(), actor.person().id());
        if (request.ownerTeamId() != null
            && !identity.activeTeam(request.ownerTeamId()).orgId().equals(request.ownerOrgId())) {
            throw new BusinessException(LearningResponseCode.INVALID_REFERENCE);
        }

        // Bước 2: kiểm tra mã và INSERT dưới khóa đã lấy, giữ nguyên tử dù DB không có UNIQUE/FK.
        if (repository.courseCodeExists(request.code().strip())) {
            throw new BusinessException(LearningResponseCode.DUPLICATE);
        }
        Course course = new Course();
        course.setCode(request.code().strip());
        course.setOwnerOrgId(request.ownerOrgId());
        course.setOwnerTeamId(request.ownerTeamId());
        course.setOwnerUserId(actor.person().id());
        course.setLifecycleStatus(CourseStatus.ACTIVE);
        repository.save(course, actor.person().id());
        CourseRevision revision = newRevision(course, request.title(), actor.person().id(), 1);

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
        validateCatalog(course, request.programId(), request.moduleId(), false);
        validateDraftSize(request);

        // Bước 2: kiểm tra tệp/quy tắc khối trước khi thay cây; lỗi bất kỳ rollback toàn bộ.
        if (request.coverAssetId() != null) {
            LearningAsset cover = usableAsset(course, request.coverAssetId());
            if (!cover.getMimeType().startsWith(MediaMime.IMAGE_PREFIX)) {
                throw new BusinessException(LearningResponseCode.ASSET_INVALID);
            }
        }
        for (var chapter : request.chapters()) {
            for (var lesson : chapter.lessons()) {
                if (!LessonType.CONTENT.equals(lesson.lessonType())) {
                    throw new BusinessException(LearningResponseCode.QUIZ_UNAVAILABLE);
                }
                for (var block : lesson.blocks()) {
                    validateBlockInput(course, block);
                }
            }
        }

        // Bước 3: chỉ xóa cây draft, không đụng các revision published/enrollment/progress lịch sử.
        clearDraftTree(revision.getId());
        revision.setProgramId(request.programId());
        revision.setModuleId(request.moduleId());
        revision.setTitle(request.title());
        revision.setSummary(request.summary());
        revision.setLevel(request.level());
        revision.setDefaultRequirement(request.defaultRequirement());
        revision.setCoverAssetId(request.coverAssetId());
        revision.setCoverIcon(request.coverIcon());
        revision.setCoverColor(request.coverColor());
        repository.save(revision, actor.person().id());
        writeTree(revision, request, actor.person().id());
        CourseCompletionRule rule = repository.rules(revision.getId()).get(0);
        rule.setRewardPoints(request.rewardPoints());
        repository.save(rule, actor.person().id());

        // Bước 4: audit và flush trong cùng transaction để caller nhận version mới dùng lần lưu sau.
        record(actor.person().id(), AuditAction.DRAFT_SAVED, course);
        repository.flush();
        repository.incrementVersion(revision);
        return detail(course, revision);
    }

    @Transactional
    public LearningDetails.CourseDetail publish(Long courseId, Long revisionId,
                                                LearningRequests.VersionRequest request) {
        // Bước 1: chỉ người có quyền sửa khóa mới được publish; version cũ không được ghi đè.
        var actor = access.actor();
        Course course = repository.require(Course.class, courseId);
        access.requireManage(actor, course);
        writeLock.acquire();
        repository.refresh(course);
        CourseRevision revision = repository.require(CourseRevision.class, revisionId);
        requireDraft(course, revision);
        version(revision, request.expectedVersion());
        requirePublishable(course, revision);

        // Bước 2: đóng băng bản nội dung và chuyển con trỏ; enrollment cũ giữ revision_id cũ.
        revision.setState(RevisionState.PUBLISHED);
        revision.setPublishedAt(Instant.now());
        revision.setPublishedBy(actor.person().id());
        course.setPublishedRevisionId(revision.getId());
        repository.save(revision, actor.person().id());
        repository.save(course, actor.person().id());
        record(actor.person().id(), AuditAction.COURSE_PUBLISHED, course);
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
        cloneContent(course, source, target, actor.person().id());
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
        cloneContent(target, sourceRevision, revision, actor.person().id());
        record(actor.person().id(), AuditAction.COURSE_COPIED, target);
        repository.flush();
        return detail(target, revision);
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
            rules.isEmpty() ? null : mapper.view(rules.get(0)));
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

    private void requireDraft(Course course, CourseRevision revision) {
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
            || !field.getOwnerOrgId().equals(course.getOwnerOrgId())
            || (published && (!CatalogStatus.PUBLISHED.equals(program.getStatus())
                || !CatalogStatus.PUBLISHED.equals(field.getStatus())))) {
            throw new BusinessException(LearningResponseCode.INVALID_REFERENCE);
        }
        if (moduleId != null) {
            BusinessModule module = repository.require(BusinessModule.class, moduleId);
            if (!module.getFieldId().equals(program.getFieldId())
                || (published && !CatalogStatus.PUBLISHED.equals(module.getStatus()))) {
                throw new BusinessException(LearningResponseCode.INVALID_REFERENCE);
            }
        }
    }

    private void validateDraftSize(LearningRequests.DraftRequest request) {
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

    private void validateBlockInput(Course course, LearningRequests.BlockInput block) {
        if ((CompletionMode.VIDEO_COVERAGE.equals(block.completionMode()) && !BlockType.VIDEO.equals(block.blockType()))
            || (CompletionMode.PDF_END.equals(block.completionMode()) && !BlockType.PDF.equals(block.blockType()))
            || (!BlockType.PROCESS.equals(block.blockType()) && !block.steps().isEmpty())
            || (BlockType.VIDEO.equals(block.blockType())
                && !CompletionMode.VIDEO_COVERAGE.equals(block.completionMode()))
            || (BlockType.TEXT.equals(block.blockType()) && BodyFormat.HTML.equals(block.bodyFormat()))) {
            throw BusinessException.validation("chapters", "INVALID_VALUE",
                "Loại khối/cách hoàn thành chưa phù hợp; văn bản hiện hỗ trợ Markdown hoặc plain.");
        }
        if (CompletionMode.VIDEO_COVERAGE.equals(block.completionMode()) && block.completionThreshold() == null) {
            throw BusinessException.validation("completionThreshold", "REQUIRED", "Vui lòng đặt tỷ lệ xem video.");
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
                default -> false;
            };
            if (!matches) {
                throw new BusinessException(LearningResponseCode.ASSET_INVALID);
            }
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
        if (!repository.assessments(revision.getId()).isEmpty()) {
            throw new BusinessException(LearningResponseCode.QUIZ_UNAVAILABLE);
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

    private void writeTree(CourseRevision revision, LearningRequests.DraftRequest request, String actor) {
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
                repository.save(lesson, actor);
                for (int bi = 0; bi < lessonInput.blocks().size(); bi++) {
                    writeBlock(lesson.getId(), lessonInput.blocks().get(bi), bi, actor);
                }
            }
        }
    }

    private void writeBlock(Long lessonId, LearningRequests.BlockInput input, int sort, String actor) {
        LessonBlock block = new LessonBlock();
        block.setLessonId(lessonId);
        block.setBlockType(input.blockType());
        block.setTitle(input.title());
        block.setBody(input.body());
        block.setBodyFormat(input.bodyFormat());
        block.setCompletionMode(input.completionMode());
        block.setCompletionThreshold(input.completionThreshold());
        block.setIsRequired(input.isRequired());
        block.setSortNo(sort);
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

    private void cloneContent(Course course, CourseRevision source, CourseRevision target, String actor) {
        var original = detail(repository.require(Course.class, source.getCourseId()), source);
        target.setBasedOnRevisionId(source.getId());
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
                l.lesson().estimatedDurationSeconds(), l.lesson().isRequired(), l.blocks().stream()
                    .map(b -> new LearningRequests.BlockInput(b.block().blockType(), b.block().title(),
                        b.block().body(), b.block().bodyFormat(), b.block().completionMode(),
                        b.block().completionThreshold(), b.block().isRequired(), b.assets().stream()
                            .map(a -> new LearningRequests.AssetUse(a.usage().assetId(), a.usage().usage(),
                                a.usage().caption(), a.usage().altText())).toList(), b.steps().stream()
                            .map(s -> new LearningRequests.StepInput(s.title(), s.description())).toList())).toList()))
                .toList())).toList();
        var payload = new LearningRequests.DraftRequest(0L, source.getProgramId(), source.getModuleId(),
            source.getTitle(), source.getSummary(), source.getLevel(), source.getDefaultRequirement(),
            source.getCoverAssetId(), source.getCoverIcon(), source.getCoverColor(),
            original.objectives().stream().map(LearningViews.CourseObjectiveView::description).toList(), chapters,
            original.completionRule().rewardPoints());
        writeTree(target, payload, actor);
        var rule = repository.rules(target.getId()).get(0);
        rule.setRewardPoints(payload.rewardPoints());
        repository.save(rule, actor);
    }

    private void record(String actor, String action, Course course) {
        audit.record(actor, action, "course", course.getId().toString(), course.getOwnerOrgId(), Map.of());
    }
}

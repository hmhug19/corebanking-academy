package vn.com.coreacademy.learning.service;

import java.util.List;
import java.util.Set;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.com.coreacademy.common.dto.PageResponse;
import vn.com.coreacademy.common.exception.BusinessException;
import vn.com.coreacademy.common.web.PageQuery;
import vn.com.coreacademy.identity.service.LearningIdentityService;
import vn.com.coreacademy.identity.service.LearningIdentityService.Actor;
import vn.com.coreacademy.learning.constants.LearningCodes.Level;
import vn.com.coreacademy.learning.constants.LearningCodes.ProgressStatus;
import vn.com.coreacademy.learning.constants.LearningCodes.Requirement;
import vn.com.coreacademy.learning.constants.LearningCodes.RevisionState;
import vn.com.coreacademy.learning.constants.LearningCodes.SearchMode;
import vn.com.coreacademy.learning.constants.LearningLimits;
import vn.com.coreacademy.learning.dto.LearningDetails;
import vn.com.coreacademy.learning.dto.LearningDetails.CourseCard;
import vn.com.coreacademy.learning.dto.LearningDetails.CourseDetail;
import vn.com.coreacademy.learning.dto.LearningDetails.LearnerCourse;
import vn.com.coreacademy.learning.dto.LearningDetails.LearnerFacets;
import vn.com.coreacademy.learning.entity.Course;
import vn.com.coreacademy.learning.entity.CourseRevision;
import vn.com.coreacademy.learning.exception.LearningResponseCode;
import vn.com.coreacademy.learning.repository.CourseRepository;
import vn.com.coreacademy.learning.repository.LearningRepository;
import vn.com.coreacademy.learning.repository.custom.CourseRepositoryCustom.CourseSearch;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class LearningSearchService {
    private final CourseRepository repository;
    private final LearningAccessPolicy access;
    private final LearningIdentityService identity;
    private final LearningQuizDirectory quizzes;
    private final LearningRepository records;
    private final LearningContentService content;

    public PageResponse<CourseCard> search(String mode, String q, Long fieldId, Long programId, Long moduleId,
                                           String level, String requirement, String learningStatus, String state,
                                           int page, int size, String sort) {
        return search(mode, q, fieldId, programId, moduleId, level, requirement, learningStatus, state,
            page, size, sort, null, null, null);
    }

    public PageResponse<CourseCard> search(String mode, String q, Long fieldId, Long programId, Long moduleId,
                                           String level, String requirement, String learningStatus, String state,
                                           int page, int size, String sort, Boolean overdue, Boolean assigned,
                                           Boolean failed) {
        var pageable = PageQuery.of(page, size, sort, Set.of("id", "title", "updateTime", "dueAt", "progressPercent",
            "startedAt", "lastAccessAt", "completedAt"));
        return PageResponse.from(query(mode, q, fieldId, programId, moduleId, level, requirement, learningStatus,
            state, overdue, assigned, failed, pageable));
    }

    public Page<CourseCard> export(String q, Long fieldId, Long programId, Long moduleId, String level,
                                   String requirement, String learningStatus, String state, Boolean overdue,
                                   Boolean assigned, Boolean failed, String sort) {
        var order = PageQuery.of(0, 1, sort, Set.of("id", "title", "updateTime", "dueAt", "progressPercent",
            "startedAt", "lastAccessAt", "completedAt")).getSort();
        return query(SearchMode.MINE, q, fieldId, programId, moduleId, level, requirement, learningStatus, state,
            overdue, assigned, failed, PageRequest.of(0, LearningLimits.MAX_EXPORT_ROWS + 1, order));
    }

    private Page<CourseCard> query(String mode, String q, Long fieldId, Long programId, Long moduleId, String level,
                                   String requirement, String learningStatus, String state, Boolean overdue,
                                   Boolean assigned, Boolean failed, Pageable pageable) {
        // Bước 1: scope xuất phát từ grant đang hiệu lực; client chỉ được gửi filter nghiệp vụ.
        var actor = access.actor();
        if (!SearchMode.MANAGE.equals(mode)) {
            access.requireLearner(actor);
        }
        allowed("level", level, Level.VALUES);
        allowed("requirement", requirement, Requirement.VALUES);
        allowed("learningStatus", learningStatus, ProgressStatus.SEARCHABLE_VALUES);
        allowed("state", state, RevisionState.VALUES);
        var scope = access.searchScope(actor);
        var filter = new CourseSearch(mode, actor.person().id(), actor.person().orgId(),
            identity.activeAncestorIds(actor.person().orgId()), scope, q, fieldId, programId, moduleId, level,
            requirement, learningStatus, state, overdue, assigned, failed, null,
            SearchMode.MANAGE.equals(mode) ? Set.of() : quizzes.failedFinalExamCourseIds(actor.person().id()));

        // Bước 2: repository lọc quyền và đếm trên grain khóa học trước phân trang; không lọc trang trong memory.
        return repository.search(filter, pageable);
    }

    /** Số khóa trong danh sách khóa của tôi theo requirement/assigned; cùng truy vấn với list nên khớp tổng của list. */
    public long mineTotal(String requirement, Boolean assigned) {
        return query(SearchMode.MINE, null, null, null, null, null, requirement, null, null, null, assigned, null,
            PageRequest.of(0, 1, Sort.by("id"))).getTotalElements();
    }

    /** Lĩnh vực, chương trình và phân hệ của các khóa caller thấy trong danh mục, theo cùng quy tắc nhìn thấy với list. */
    public LearnerFacets facets() {
        var actor = access.actor();
        access.requireLearner(actor);
        return repository.facets(catalogFilter(actor, null, Set.of()));
    }

    /**
     * Một khóa học viên thấy trong danh mục kèm nội dung. Thẻ lấy từ search CATALOG nên quy tắc nhìn thấy, bản revision
     * (bản đã nhận hoặc bản xuất bản) và cờ failed giống danh sách; khóa không thấy được là NOT_FOUND. Chưa đăng ký thì
     * chỉ trả cây chương/bài, không trả nội dung khối.
     */
    public LearnerCourse course(Long courseId) {
        var actor = access.actor();
        access.requireLearner(actor);
        var cards = repository.search(catalogFilter(actor, courseId,
            quizzes.failedFinalExamCourseIds(actor.person().id())), PageRequest.of(0, 1, Sort.by("id")));
        if (cards.isEmpty()) {
            throw new BusinessException(LearningResponseCode.NOT_FOUND);
        }
        var card = cards.getContent().get(0);
        var detail = content.detail(records.require(Course.class, courseId),
            records.require(CourseRevision.class, card.revisionId()));
        return new LearnerCourse(card, card.enrollmentId() == null ? withoutBlocks(detail) : detail);
    }

    private CourseSearch catalogFilter(Actor actor, Long courseId, Set<Long> failedCourseIds) {
        return new CourseSearch(SearchMode.CATALOG, actor.person().id(), actor.person().orgId(),
            identity.activeAncestorIds(actor.person().orgId()), access.searchScope(actor), null, null, null, null,
            null, null, null, null, null, null, null, courseId, failedCourseIds);
    }

    private static CourseDetail withoutBlocks(CourseDetail detail) {
        var chapters = detail.chapters().stream().map(chapter -> new LearningDetails.ChapterDetail(chapter.chapter(),
            chapter.lessons().stream().map(lesson -> new LearningDetails.LessonDetail(lesson.lesson(), List.of()))
                .toList())).toList();
        return new CourseDetail(detail.course(), detail.revision(), detail.objectives(), chapters,
            detail.completionRule());
    }

    private void allowed(String field, String value, Set<String> options) {
        if (value != null && !options.contains(value)) {
            throw BusinessException.validation(field, "INVALID_VALUE", "Giá trị bộ lọc chưa hợp lệ.");
        }
    }
}

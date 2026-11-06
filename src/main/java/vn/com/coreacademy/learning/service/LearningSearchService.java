package vn.com.coreacademy.learning.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
import vn.com.coreacademy.learning.constants.LearningCodes.CourseStatus;
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
import vn.com.coreacademy.learning.dto.LearningFeedbackViews.RatingSummary;
import vn.com.coreacademy.learning.entity.Course;
import vn.com.coreacademy.learning.entity.CourseRevision;
import vn.com.coreacademy.learning.exception.LearningResponseCode;
import vn.com.coreacademy.learning.repository.CourseRepository;
import vn.com.coreacademy.learning.repository.LearningRepository;
import vn.com.coreacademy.learning.repository.LessonFeedbackReadRepository;
import vn.com.coreacademy.learning.repository.custom.CourseRepositoryCustom.CourseSearch;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class LearningSearchService {
    private static final int MINE_IDS_PAGE_SIZE = 200;

    private final CourseRepository repository;
    private final LearningAccessPolicy access;
    private final LearningIdentityService identity;
    private final LearningQuizDirectory quizzes;
    private final LearningRepository records;
    private final LearningContentService content;
    private final LessonFeedbackReadRepository feedback;

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
        return PageResponse.from(withRatings(withBestScores(mode, query(mode, q, fieldId, programId, moduleId, level,
            requirement, learningStatus, state, overdue, assigned, failed, pageable))));
    }

    public Page<CourseCard> export(String q, Long fieldId, Long programId, Long moduleId, String level,
                                   String requirement, String learningStatus, String state, Boolean overdue,
                                   Boolean assigned, Boolean failed, String sort) {
        var order = PageQuery.of(0, 1, sort, Set.of("id", "title", "updateTime", "dueAt", "progressPercent",
            "startedAt", "lastAccessAt", "completedAt")).getSort();
        return withBestScores(SearchMode.MINE, query(SearchMode.MINE, q, fieldId, programId, moduleId, level,
            requirement, learningStatus, state, overdue, assigned, failed,
            PageRequest.of(0, LearningLimits.MAX_EXPORT_ROWS + 1, order)));
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
        allowed("state", state, Set.of(RevisionState.DRAFT, RevisionState.PUBLISHED,
            CourseStatus.PAUSED, CourseStatus.ARCHIVED));
        var scope = access.searchScope(actor);
        var filter = new CourseSearch(mode, actor.person().id(), actor.person().orgId(),
            identity.activeAncestorIds(actor.person().orgId()), scope, q, fieldId, programId, moduleId, level,
            requirement, learningStatus, state, overdue, assigned, failed, null,
            SearchMode.MANAGE.equals(mode) ? Set.of() : quizzes.failedFinalExamCourseIds(actor.person().id()));

        // Bước 2: repository lọc quyền và đếm trên grain khóa học trước phân trang; không lọc trang trong memory.
        return repository.search(filter, pageable);
    }

    /**
     * Điền điểm cao nhất của bài thi cuối khóa vào từng thẻ của trang bằng một lần gọi cổng quiz cho cả trang. Chế độ
     * MANAGE là danh sách của giảng viên, không phải của người học nên không gọi cổng và để null.
     */
    private Page<CourseCard> withBestScores(String mode, Page<CourseCard> cards) {
        if (SearchMode.MANAGE.equals(mode) || cards.isEmpty()) {
            return cards;
        }
        Map<Long, BigDecimal> finalExamBest = new HashMap<>();
        quizzes.scores(access.actor().person().id(), cards.map(CourseCard::courseId).toList()).stream()
            .filter(LearningQuizDirectory.QuizScore::finalExam)
            .forEach(score -> finalExamBest.put(score.courseId(), score.bestScore()));
        return cards.map(card -> card.withBestScorePercent(finalExamBest.get(card.courseId())));
    }

    /**
     * Điền điểm đánh giá của khóa vào từng thẻ của trang bằng một truy vấn gom nhóm cho cả trang, ở mọi chế độ kể cả
     * MANAGE vì đánh giá là của khóa chứ không của người xem. Khóa chưa có đánh giá để rating là null.
     */
    private Page<CourseCard> withRatings(Page<CourseCard> cards) {
        if (cards.isEmpty()) {
            return cards;
        }
        Map<Long, RatingSummary> ratings = feedback.courseRatings(cards.map(CourseCard::courseId).toList());
        return cards.map(card -> card.withRating(ratings.get(card.courseId())));
    }

    /** Số khóa trong danh sách khóa của tôi theo requirement/assigned; cùng truy vấn với list nên khớp tổng của list. */
    public long mineTotal(String requirement, Boolean assigned) {
        return query(SearchMode.MINE, null, null, null, null, null, requirement, null, null, null, assigned, null,
            PageRequest.of(0, 1, Sort.by("id"))).getTotalElements();
    }

    /**
     * ID mọi khóa trong danh sách khóa của tôi, cùng truy vấn với list và mineTotal nên các số trên tổng quan học tập
     * và các tab dùng một tập khóa. Đọc từng trang để không cắt im lặng khi caller có nhiều khóa.
     */
    public List<Long> mineCourseIds() {
        var ids = new ArrayList<Long>();
        Page<CourseCard> page;
        int number = 0;
        do {
            page = query(SearchMode.MINE, null, null, null, null, null, null, null, null, null, null, null,
                PageRequest.of(number++, MINE_IDS_PAGE_SIZE, Sort.by("id")));
            page.forEach(card -> ids.add(card.courseId()));
        } while (page.hasNext());
        return ids;
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
        var card = withRatings(withBestScores(SearchMode.CATALOG, cards)).getContent().get(0);
        var detail = content.detail(records.require(Course.class, courseId),
            records.require(CourseRevision.class, card.revisionId()));
        return new LearnerCourse(card, card.enrollmentId() == null ? withoutBlocks(detail) : detail);
    }

    /**
     * Học viên đang thấy đúng revision này của khóa trong danh mục (bản đã nhận hoặc bản xuất bản), theo cùng quy tắc
     * nhìn thấy của danh sách. Dùng cho ảnh bìa: ai thấy thẻ khóa thì được xem ảnh bìa của bản trên thẻ.
     */
    public boolean seesRevision(Actor actor, Long courseId, Long revisionId) {
        var cards = repository.search(catalogFilter(actor, courseId, Set.of()), PageRequest.of(0, 1, Sort.by("id")));
        return !cards.isEmpty() && revisionId.equals(cards.getContent().get(0).revisionId());
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
            detail.completionRule(), detail.finalQuizId());
    }

    private void allowed(String field, String value, Set<String> options) {
        if (value != null && !options.contains(value)) {
            throw BusinessException.validation(field, "INVALID_VALUE", "Giá trị bộ lọc chưa hợp lệ.");
        }
    }
}

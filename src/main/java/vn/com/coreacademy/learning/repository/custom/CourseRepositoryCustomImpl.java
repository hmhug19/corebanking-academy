package vn.com.coreacademy.learning.repository.custom;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

import jakarta.persistence.EntityManager;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.annotation.Transactional;
import vn.com.coreacademy.common.persistence.NativeSearchQuery;
import vn.com.coreacademy.learning.constants.LearningCodes.AssignmentStatus;
import vn.com.coreacademy.learning.constants.LearningCodes.CatalogStatus;
import vn.com.coreacademy.learning.constants.LearningCodes.CourseStatus;
import vn.com.coreacademy.learning.constants.LearningCodes.DeadlineStatus;
import vn.com.coreacademy.learning.constants.LearningCodes.ProgressStatus;
import vn.com.coreacademy.learning.constants.LearningCodes.RecipientStatus;
import vn.com.coreacademy.learning.constants.LearningCodes.Requirement;
import vn.com.coreacademy.learning.constants.LearningCodes.RevisionState;
import vn.com.coreacademy.learning.constants.LearningCodes.SearchMode;
import vn.com.coreacademy.learning.constants.LearningCodes.ShareStatus;
import vn.com.coreacademy.learning.dto.LearningDetails.CourseCard;
import vn.com.coreacademy.learning.dto.LearningDetails.Facet;
import vn.com.coreacademy.learning.dto.LearningDetails.FieldFacet;
import vn.com.coreacademy.learning.dto.LearningDetails.LearnerFacets;

@Transactional(readOnly = true)
@RequiredArgsConstructor
public class CourseRepositoryCustomImpl implements CourseRepositoryCustom {
    /**
     * Khóa kèm lần học hiện hành của caller (e), revision đang xem (r), chương trình (p), lĩnh vực (f) và phân hệ (m).
     * Dùng chung cho search và facets để hai nơi cùng một tập khóa nhìn thấy; cần bind bằng bindCourseBase.
     */
    private static final String COURSE_BASE = """
            from learning_hub.courses c
            left join lateral (
                select x.enrollment_id, x.revision_id, x.status, x.progress_percent, x.enrollment_origin,
                       x.cycle_no, x.started_at, x.last_access_at, x.completed_at, x.last_lesson_id, x.last_block_id
                from learning_hub.course_enrollments x
                where x.course_id = c.course_id and x.user_id = :actor and x.status <> :codeWithdrawn
                order by x.cycle_no desc limit 1
            ) e on true
            join lateral (
                select x.revision_id, x.program_id, x.module_id, x.level, x.title, x.default_requirement,
                       x.state, x.update_time, x.cover_asset_id
                from learning_hub.course_revisions x
                where x.course_id = c.course_id
                  and (:mode = :codeManage or x.revision_id = coalesce(e.revision_id, c.published_revision_id))
                order by x.revision_no desc limit 1
            ) r on true
            left join learning_hub.training_programs p on p.program_id = r.program_id
            left join learning_hub.training_fields f on f.field_id = p.field_id
            left join learning_hub.business_modules m on m.module_id = r.module_id
            """;

    /** Điều kiện học viên thấy khóa; dùng chung cho search và facets, bind bằng bindLearnerVisibility. */
    private static final String LEARNER_VISIBILITY = """
        e.enrollment_id is not null
        or (c.lifecycle_status = :codeCourseActive and r.state = :codeRevisionPublished
            and p.status = :codeCatalogPublished and f.status = :codeCatalogPublished and (
                c.owner_org_id = :actorOrg
                or exists (
                    select 1 from learning_hub.course_shares s
                    where s.course_id = c.course_id and s.status = :codeShareActive
                      and (s.org_id = :actorOrg or (s.include_descendants and s.org_id in (:ancestors)))
                )
            ))
        """;

    private final EntityManager entityManager;

    /** LATERAL lấy đúng một revision/enrollment; mọi filter và scope dùng chung cho page/count. */
    @Override
    public Page<CourseCard> search(CourseSearch filter, Pageable pageable) {
        NativeSearchQuery sql = new NativeSearchQuery(COURSE_BASE + """
            left join learning_hub.course_revisions published on published.revision_id = c.published_revision_id
            left join lateral (
                select min(ar.effective_due_at) as due_at,
                       bool_or(ar.requirement = :codeMandatory and ar.effective_start_at <= current_timestamp) as
                       mandatory,
                       count(*) > 0 as assigned
                from learning_hub.assignment_recipients ar
                join learning_hub.course_assignments a on a.assignment_id = ar.assignment_id
                    and a.status = :codeAssignmentActive
                where ar.enrollment_id = e.enrollment_id and ar.status = :codeRecipientActive
                  and ar.effective_start_at <= current_timestamp
            ) obligations on true
            left join lateral (
                select count(*) as lesson_count, coalesce(sum(l.estimated_duration_seconds), 0) as duration,
                       count(*) filter (where l.is_required) as required_count,
                       count(*) filter (where l.is_required and exists (
                           select 1 from learning_hub.lesson_progress lp
                           where lp.enrollment_id = e.enrollment_id and lp.lesson_id = l.lesson_id
                             and lp.status = :codeCompleted)) as completed_count
                from learning_hub.course_chapters ch
                join learning_hub.course_lessons l on l.chapter_id = ch.chapter_id
                where ch.revision_id = r.revision_id
            ) stats on true
            left join learning_hub.block_progress resume
                on resume.enrollment_id = e.enrollment_id and resume.block_id = e.last_block_id
            left join lateral (
                select count(distinct ar.user_id) as assigned_count
                from learning_hub.course_assignments a
                join learning_hub.assignment_recipients ar on ar.assignment_id = a.assignment_id
                where a.course_id = c.course_id
                  and a.status = :codeAssignmentActive and ar.status = :codeRecipientActive
            ) audience on true
            """);
        bindCourseBase(sql::bind, filter);
        sql.bind("codeMandatory", Requirement.MANDATORY);
        sql.bind("codeAssignmentActive", AssignmentStatus.ACTIVE);
        sql.bind("codeRecipientActive", RecipientStatus.ACTIVE);
        sql.bind("codeCompleted", ProgressStatus.COMPLETED);

        if (SearchMode.MANAGE.equals(filter.mode())) {
            applyManagementScope(sql, filter);
        } else {
            applyLearnerVisibility(sql, filter);
            if (SearchMode.MINE.equals(filter.mode())) {
                sql.and("e.enrollment_id is not null");
            }
        }
        String term = NativeSearchQuery.containsPattern(filter.q());
        if (term != null) {
            sql.and("lower(c.code) like :term escape '!' or lower(r.title) like :term escape '!'");
            sql.bind("term", term);
        }
        if (filter.courseId() != null) {
            sql.and("c.course_id = :courseId");
            sql.bind("courseId", filter.courseId());
        }
        if (filter.fieldId() != null) {
            sql.and("p.field_id = :field");
            sql.bind("field", filter.fieldId());
        }
        if (filter.programId() != null) {
            sql.and("r.program_id = :program");
            sql.bind("program", filter.programId());
        }
        if (filter.moduleId() != null) {
            sql.and("r.module_id = :module");
            sql.bind("module", filter.moduleId());
        }
        if (filter.withoutModule()) {
            sql.and("m.module_id is null");
        }
        if (filter.level() != null) {
            sql.and("r.level = :level");
            sql.bind("level", filter.level());
        }
        if (filter.requirement() != null) {
            sql.and("(case when obligations.mandatory then :codeMandatory else r.default_requirement end) = :req");
            sql.bind("req", filter.requirement());
            if (SearchMode.MINE.equals(filter.mode()) && Requirement.MANDATORY.equals(filter.requirement())) {
                sql.and("coalesce(obligations.mandatory, false)");
            }
        }
        if (filter.learningStatus() != null) {
            sql.and("coalesce(e.status, :codeNotStarted) = :learningStatus");
            sql.bind("learningStatus", filter.learningStatus());
            sql.bind("codeNotStarted", ProgressStatus.NOT_STARTED);
        }
        if (filter.publicationState() != null) {
            sql.and((CourseStatus.PAUSED.equals(filter.publicationState())
                || CourseStatus.ARCHIVED.equals(filter.publicationState()))
                ? "c.lifecycle_status = :state" : "r.state = :state");
            sql.bind("state", filter.publicationState());
        }
        if (filter.assigned() != null) {
            sql.and("coalesce(obligations.assigned, false) = :assigned");
            sql.bind("assigned", filter.assigned());
        }
        if (filter.overdue() != null) {
            sql.and("""
                coalesce(obligations.due_at < current_timestamp and e.status <> :codeCompleted, false) = :overdue
                """);
            sql.bind("overdue", filter.overdue());
        }
        if (filter.learningStatus() == null) {
            sql.bindProjection("codeNotStarted", ProgressStatus.NOT_STARTED);
        }
        List<Long> failedIds = filter.failedCourseIds().isEmpty() ? List.of(-1L) : List.copyOf(filter.failedCourseIds());
        if (filter.failed() == null) {
            sql.bindProjection("failedIds", failedIds);
        } else {
            sql.and("(c.course_id in (:failedIds)) = :failed");
            sql.bind("failedIds", failedIds);
            sql.bind("failed", filter.failed());
        }
        sql.bindProjection("codeNotActive", DeadlineStatus.NOT_ACTIVE);
        sql.bindProjection("codeNoDeadline", DeadlineStatus.NO_DEADLINE);
        sql.bindProjection("codeCompletedLate", DeadlineStatus.COMPLETED_LATE);
        sql.bindProjection("codeCompletedOnTime", DeadlineStatus.COMPLETED_ON_TIME);
        sql.bindProjection("codeOverdueActive", DeadlineStatus.OVERDUE_ACTIVE);
        sql.bindProjection("codeOnTimeActive", DeadlineStatus.ON_TIME_ACTIVE);
        // Ưu tiên khóa chưa hoàn thành trên toàn tập dữ liệu, trước OFFSET/LIMIT; không suy từ % nội dung.
        boolean unfinishedFirst = SearchMode.CATALOG.equals(filter.mode())
            || (SearchMode.MINE.equals(filter.mode()) && filter.learningStatus() == null
                && (Requirement.MANDATORY.equals(filter.requirement()) || Boolean.TRUE.equals(filter.assigned())));
        Sort order = Sort.by(pageable.getSort().stream()
            .map(item -> Set.of("lastAccessAt", "publishedAt").contains(item.getProperty()) ? item.nullsLast() : item).toList());
        Pageable ordered = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
            unfinishedFirst ? Sort.by("completionPriority").and(order) : order);
        return sql.page(entityManager, """
            select c.course_id, r.revision_id, c.code, r.title, r.program_id, r.module_id, r.level,
                   case when obligations.mandatory then :codeMandatory else r.default_requirement end,
                   r.state, c.lifecycle_status, stats.lesson_count, stats.duration,
                   audience.assigned_count, e.enrollment_id, coalesce(e.status, :codeNotStarted),
                   coalesce(e.progress_percent, 0), obligations.due_at, coalesce(obligations.assigned, false),
                   coalesce(obligations.due_at < current_timestamp and e.status <> :codeCompleted, false),
                   r.update_time,
                   e.cycle_no, e.started_at, e.last_access_at, e.completed_at, e.last_lesson_id, e.last_block_id,
                   stats.completed_count, stats.required_count,
                   case when e.enrollment_id is null then :codeNotActive
                        when obligations.due_at is null then :codeNoDeadline
                        when e.completed_at > obligations.due_at then :codeCompletedLate
                        when e.completed_at is not null then :codeCompletedOnTime
                        when obligations.due_at < current_timestamp then :codeOverdueActive
                        else :codeOnTimeActive end,
                   resume.media_asset_id, resume.last_pdf_page, resume.last_video_position_seconds,
                   f.field_id, f.name, p.name, m.name, c.course_id in (:failedIds), cast(null as numeric),
                   r.cover_asset_id
            """, CourseCard.class, ordered, Map.of("id", "c.course_id", "title", "r.title",
                "completionPriority", "case when e.status = :codeCompleted then 1 else 0 end",
                "updateTime", "r.update_time", "dueAt", "obligations.due_at", "progressPercent", "e.progress_percent",
                "startedAt", "e.started_at", "lastAccessAt", "e.last_access_at",
                "completedAt", "e.completed_at", "publishedAt", "published.published_at"));
    }

    /** Lĩnh vực, chương trình, phân hệ của các khóa học viên thấy trong danh mục; filter là mode CATALOG. */
    @Override
    public LearnerFacets facets(CourseSearch filter) {
        return new LearnerFacets(
            facetRows("""
                select tf.field_id, tf.code, tf.name from learning_hub.training_fields tf
                where tf.field_id in (%s)
                order by tf.sort_no, tf.name
                """.formatted(visibleValues("p.field_id")), Facet.class, filter),
            facetRows("""
                select tp.program_id, tp.field_id, tp.code, tp.name from learning_hub.training_programs tp
                where tp.program_id in (%s)
                order by tp.sort_no, tp.name
                """.formatted(visibleValues("r.program_id")), FieldFacet.class, filter),
            facetRows("""
                select tm.module_id, tm.field_id, tm.code, tm.name from learning_hub.business_modules tm
                where tm.module_id in (%s)
                order by tm.sort_no, tm.name
                """.formatted(visibleValues("r.module_id")), FieldFacet.class, filter),
            facetRows("""
                select distinct p.field_id
                %s
                where (%s) and m.module_id is null
                order by p.field_id
                """.formatted(COURSE_BASE, LEARNER_VISIBILITY), Long.class, filter));
    }

    private static String visibleValues(String column) {
        return "select " + column + "\n" + COURSE_BASE + "where (" + LEARNER_VISIBILITY + ") and " + column
            + " is not null";
    }

    private <T> List<T> facetRows(String sql, Class<T> type, CourseSearch filter) {
        var query = entityManager.createNativeQuery(sql, type);
        bindCourseBase(query::setParameter, filter);
        bindLearnerVisibility(query::setParameter, filter);
        return query.getResultList();
    }

    @Override
    public EnrollmentCounts summary(String userId) {
        return (EnrollmentCounts) entityManager.createNativeQuery("""
            select count(*) as total, count(*) filter (where e.status = :codeCompleted) as completed,
                   count(*) filter (where e.status = :codeInProgress) as in_progress,
                   count(*) filter (where e.status = :codeNotStarted) as not_started,
                   count(*) filter (where e.status <> :codeCompleted and due.due_at < current_timestamp) as overdue
            from learning_hub.course_enrollments e
            left join lateral (
                select min(ar.effective_due_at) as due_at
                from learning_hub.assignment_recipients ar
                join learning_hub.course_assignments a on a.assignment_id = ar.assignment_id
                    and a.status = :codeAssignmentActive
                where ar.enrollment_id = e.enrollment_id and ar.status = :codeRecipientActive
                  and ar.effective_start_at <= current_timestamp
            ) due on true
            where e.user_id = :user and e.status <> :codeWithdrawn
              and not exists (
                  select 1 from learning_hub.course_enrollments later
                  where later.course_id = e.course_id and later.user_id = e.user_id
                    and later.status <> :codeWithdrawn and later.cycle_no > e.cycle_no
              )
            """, EnrollmentCounts.class)
            .setParameter("user", userId)
            .setParameter("codeCompleted", ProgressStatus.COMPLETED)
            .setParameter("codeInProgress", ProgressStatus.IN_PROGRESS)
            .setParameter("codeNotStarted", ProgressStatus.NOT_STARTED)
            .setParameter("codeWithdrawn", ProgressStatus.WITHDRAWN)
            .setParameter("codeAssignmentActive", AssignmentStatus.ACTIVE)
            .setParameter("codeRecipientActive", RecipientStatus.ACTIVE).getSingleResult();
    }

    /** Khóa học viên thấy được: đã đăng ký, hoặc khóa đang hoạt động đã xuất bản thuộc đơn vị mình hoặc được chia sẻ. */
    private void applyLearnerVisibility(NativeSearchQuery sql, CourseSearch filter) {
        sql.and(LEARNER_VISIBILITY);
        bindLearnerVisibility(sql::bind, filter);
    }

    private static void bindLearnerVisibility(BiConsumer<String, Object> bind, CourseSearch filter) {
        bind.accept("codeCourseActive", CourseStatus.ACTIVE);
        bind.accept("codeRevisionPublished", RevisionState.PUBLISHED);
        bind.accept("codeCatalogPublished", CatalogStatus.PUBLISHED);
        bind.accept("codeShareActive", ShareStatus.ACTIVE);
        bind.accept("actorOrg", filter.actorOrgId() == null ? -1L : filter.actorOrgId());
        bind.accept("ancestors", filter.ancestorIds().isEmpty() ? List.of(-1L) : filter.ancestorIds());
    }

    private static void bindCourseBase(BiConsumer<String, Object> bind, CourseSearch filter) {
        bind.accept("actor", filter.actorId());
        bind.accept("codeWithdrawn", ProgressStatus.WITHDRAWN);
        bind.accept("codeManage", SearchMode.MANAGE);
        bind.accept("mode", filter.mode());
    }

    private void applyManagementScope(NativeSearchQuery sql, CourseSearch filter) {
        if (filter.scope().global()) {
            return;
        }
        sql.and("""
            c.owner_org_id in (:visibleOrgs) or c.owner_team_id in (:visibleTeams)
            or (:own and c.owner_user_id = :actor and c.owner_org_id = :ownOrg)
            """);
        sql.bind("visibleOrgs", filter.scope().orgIds().isEmpty() ? List.of(-1L) : filter.scope().orgIds());
        sql.bind("visibleTeams", filter.scope().teamIds().isEmpty() ? List.of(-1L) : filter.scope().teamIds());
        sql.bind("own", filter.scope().own());
        sql.bind("ownOrg", filter.actorOrgId() == null ? -1L : filter.actorOrgId());
    }
}

package vn.com.coreacademy.learning.repository.custom;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;
import vn.com.coreacademy.common.persistence.NativeSearchQuery;
import vn.com.coreacademy.learning.constants.LearningCodes.AssetUsage;
import vn.com.coreacademy.learning.constants.LearningCodes.AssignmentStatus;
import vn.com.coreacademy.learning.constants.LearningCodes.CatalogStatus;
import vn.com.coreacademy.learning.constants.LearningCodes.CourseStatus;
import vn.com.coreacademy.learning.constants.LearningCodes.LessonType;
import vn.com.coreacademy.learning.constants.LearningCodes.ProgressStatus;
import vn.com.coreacademy.learning.constants.LearningCodes.RecipientStatus;
import vn.com.coreacademy.learning.constants.LearningCodes.Requirement;
import vn.com.coreacademy.learning.constants.LearningCodes.ShareStatus;
import vn.com.coreacademy.learning.dto.LearningAnalytics;
import vn.com.coreacademy.learning.dto.LearningReportFilter;
import vn.com.coreacademy.learning.service.LearningAssessmentAccess.ReportScope;

/** Một dòng/enrollment hiện hành; mọi filter, scope, list/count và tổng hợp dùng cùng nguồn. */
@Repository
@RequiredArgsConstructor
public class LearningAnalyticsRepository {
    private static final Map<String, String> GROUPS = Map.of("user", "x.user_id", "course", "x.course_id",
        "program", "x.program_id", "org", "x.org_id", "team", "x.team_id");
    public static final Set<String> GROUP_TYPES = GROUPS.keySet();
    public static final Set<String> SORTS = Set.of("id", "progressPercent", "dueAt", "lastAccessAt", "enrolledAt");
    private static final Map<String, String> ORDER_COLUMNS = Map.of("id", "x.id",
        "progressPercent", "x.progress_percent", "dueAt", "x.due_at", "lastAccessAt", "x.last_access_at",
        "enrolledAt", "x.enrolled_at");
    private static final String COUNTS = """
        count(*), count(distinct x.user_id), count(distinct x.course_id),
        count(*) filter (where x.status = :completed), count(*) filter (where x.status = :inProgress),
        count(*) filter (where x.status = :notStarted), count(*) filter (where x.overdue),
        coalesce(round(avg(x.progress_percent), 2), 0),
        coalesce(round(100.0 * count(*) filter (where x.status = :completed) / nullif(count(*), 0), 2), 0)
        """;
    private static final String ROW = """
        select x.id, x.user_id, x.course_id, x.course_code, x.course_title, x.revision_id,
               x.program_id, x.program_name, x.org_id, x.team_id, x.status, x.progress_percent,
               x.assigned, x.mandatory, x.enrolled_at, x.started_at, x.last_access_at, x.completed_at,
               x.due_at, x.overdue, x.can_study
        """;

    private final EntityManager entityManager;

    public record Criteria(LearningReportFilter filter, ReportScope scope, Set<Long> filterOrgs,
                           String learnerId, Long actorOrgId, Set<Long> ancestors) {
    }

    private record Source(String sql, Map<String, Object> params) {
        Query bind(Query query) {
            params.forEach(query::setParameter);
            return query;
        }
    }

    public LearningAnalytics.Counts counts(Criteria criteria) {
        Source source = source(criteria);
        return (LearningAnalytics.Counts) source.bind(entityManager.createNativeQuery("""
            select count(*), count(distinct x.user_id), count(distinct x.course_id),
                   count(*) filter (where x.assigned), count(*) filter (where x.mandatory),
                   count(*) filter (where x.status = :completed),
                   count(*) filter (where x.status = :inProgress),
                   count(*) filter (where x.status = :notStarted), count(*) filter (where x.overdue),
                   coalesce(round(avg(x.progress_percent), 2), 0),
                   coalesce(round(100.0 * count(*) filter (where x.status = :completed)
                       / nullif(count(*), 0), 2), 0)
            """ + source.sql(), LearningAnalytics.Counts.class))
            .setParameter("inProgress", ProgressStatus.IN_PROGRESS)
            .setParameter("notStarted", ProgressStatus.NOT_STARTED).getSingleResult();
    }

    public Page<LearningAnalytics.Enrollment> enrollments(Criteria criteria, Pageable page) {
        Source source = source(criteria);
        NativeSearchQuery sql = new NativeSearchQuery("from (select x.* " + source.sql() + ") x\n");
        source.params().forEach(sql::bind);
        return sql.page(entityManager, ROW, LearningAnalytics.Enrollment.class, page, ORDER_COLUMNS);
    }

    /** Hạn đang hiệu lực; bỏ khóa hoàn thành, giới hạn tại DB, không tải tất cả rồi cắt trong memory. */
    public List<LearningAnalytics.Enrollment> deadlines(Criteria criteria, int limit) {
        Source source = source(criteria);
        return source.bind(entityManager.createNativeQuery(ROW + source.sql() + """
            and x.due_at is not null and x.status <> :completed
            order by x.due_at, x.id
            """, LearningAnalytics.Enrollment.class)).setMaxResults(limit).getResultList().stream()
            .map(LearningAnalytics.Enrollment.class::cast).toList();
    }

    public Page<LearningAnalytics.Group> groups(Criteria criteria, String groupBy, Pageable page) {
        Source source = source(criteria);
        String key = GROUPS.get(groupBy);
        String name = switch (groupBy) {
            case "course" -> "max(x.course_title)";
            case "program" -> "max(x.program_name)";
            default -> "cast(null as varchar)";
        };
        NativeSearchQuery sql = new NativeSearchQuery("from (select cast(" + key + " as varchar) as group_id, "
            + name + " as group_name, " + COUNTS + source.sql() + " group by " + key + ") g\n");
        source.params().forEach(sql::bind);
        sql.bind("inProgress", ProgressStatus.IN_PROGRESS);
        sql.bind("notStarted", ProgressStatus.NOT_STARTED);
        return sql.page(entityManager, "select g.*\n", LearningAnalytics.Group.class, page,
            Map.of("id", "g.group_id"));
    }

    /** Bài/khối chưa hoàn thành gần nhất; bài quiz không có block vẫn trả lessonId và quizId để điều hướng. */
    public LearningAnalytics.NextAction next(Criteria criteria) {
        Source source = source(criteria);
        Query query = source.bind(entityManager.createNativeQuery("""
            select x.id, x.course_id, x.course_title, n.lesson_id, n.block_id, n.block_type, n.quiz_id,
                   n.media_asset_id, n.last_pdf_page, n.last_video_position_seconds
            from (
                select x.*
            """ + source.sql() + """
            ) x
            cross join lateral (
                select l.lesson_id, b.block_id, b.block_type, l.quiz_id,
                       coalesce(b.media_asset_id, ba.asset_id) as media_asset_id,
                       bp.last_pdf_page, bp.last_video_position_seconds
                from learning_hub.course_chapters ch
                join learning_hub.course_lessons l on l.chapter_id = ch.chapter_id
                left join learning_hub.lesson_blocks b on b.lesson_id = l.lesson_id
                left join learning_hub.lesson_progress lp on lp.enrollment_id = x.id and lp.lesson_id = l.lesson_id
                left join learning_hub.block_progress bp on bp.enrollment_id = x.id and bp.block_id = b.block_id
                left join lateral (
                    select a.asset_id from learning_hub.block_assets a
                    where a.block_id = b.block_id and a.usage = :primaryUsage
                    order by a.sort_no, a.block_asset_id limit 1
                ) ba on true
                where ch.revision_id = x.revision_id
                  and ((l.lesson_type = :quizLesson and coalesce(lp.progress_percent, 0) < 100)
                    or (b.block_id is not null and coalesce(bp.progress_percent, 0) < 100))
                order by case when b.block_id = x.last_block_id then 0 else 1 end,
                         ch.sort_no, ch.chapter_id, l.sort_no, l.lesson_id, b.sort_no, b.block_id
                limit 1
            ) n
            where x.can_study and x.status <> :completed
            order by x.last_access_at desc nulls last, x.enrolled_at, x.id
            """, LearningAnalytics.NextAction.class));
        List<?> rows = query.setParameter("primaryUsage", AssetUsage.PRIMARY)
            .setParameter("quizLesson", LessonType.QUIZ).setMaxResults(1).getResultList();
        return rows.isEmpty() ? null : (LearningAnalytics.NextAction) rows.get(0);
    }

    private Source source(Criteria criteria) {
        Map<String, Object> params = parameters(criteria);
        List<String> conditions = new ArrayList<>();
        LearningReportFilter filter = criteria.filter();
        if (criteria.learnerId() != null) {
            conditions.add("x.user_id = :learner");
            params.put("learner", criteria.learnerId());
        } else if (!criteria.scope().global()) {
            conditions.add("(x.org_id in (:scopeOrgs) or x.team_id in (:scopeTeams))");
            params.put("scopeOrgs", nonEmpty(criteria.scope().orgIds()));
            params.put("scopeTeams", nonEmpty(criteria.scope().teamIds()));
        }
        add(conditions, params, "x.user_id = :user", "user", filter.userId());
        if (filter.orgId() != null) {
            conditions.add("x.org_id in (:filterOrgs)");
            params.put("filterOrgs", nonEmpty(criteria.filterOrgs()));
        }
        add(conditions, params, "x.team_id = :team", "team", filter.teamId());
        add(conditions, params, "x.course_id = :course", "course", filter.courseId());
        add(conditions, params, "x.program_id = :program", "program", filter.programId());
        add(conditions, params, "x.field_id = :field", "field", filter.fieldId());
        add(conditions, params, "x.status = :status", "status", filter.status());
        add(conditions, params, "x.overdue = :overdue", "overdue", filter.overdue());
        add(conditions, params, "x.enrolled_at >= :from", "from", filter.from());
        add(conditions, params, "x.enrolled_at < :to", "to", filter.to());
        String term = NativeSearchQuery.containsPattern(filter.q());
        add(conditions, params, "(lower(x.course_code) like :term escape '!' "
            + "or lower(x.course_title) like :term escape '!')", "term", term);
        String where = conditions.isEmpty() ? "where 1 = 1\n" : "where " + String.join(" and ", conditions) + "\n";
        return new Source(BASE + where, params);
    }

    private Map<String, Object> parameters(Criteria criteria) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("withdrawn", ProgressStatus.WITHDRAWN);
        params.put("completed", ProgressStatus.COMPLETED);
        params.put("assignmentActive", AssignmentStatus.ACTIVE);
        params.put("recipientActive", RecipientStatus.ACTIVE);
        params.put("mandatory", Requirement.MANDATORY);
        params.put("courseActive", CourseStatus.ACTIVE);
        params.put("catalogPublished", CatalogStatus.PUBLISHED);
        params.put("shareActive", ShareStatus.ACTIVE);
        params.put("actorOrg", criteria.actorOrgId() == null ? -1L : criteria.actorOrgId());
        params.put("ancestors", nonEmpty(criteria.ancestors()));
        return params;
    }

    private void add(List<String> conditions, Map<String, Object> params, String sql, String key, Object value) {
        if (value != null) {
            conditions.add(sql);
            params.put(key, value);
        }
    }

    private List<Long> nonEmpty(Set<Long> ids) {
        return ids.isEmpty() ? List.of(-1L) : List.copyOf(ids);
    }

    private static final String BASE = """
        from (
            select e.enrollment_id as id, e.user_id, e.course_id, c.code as course_code, r.title as course_title,
                   e.revision_id, r.program_id, p.name as program_name, p.field_id,
                   e.org_id_snapshot as org_id, e.team_id_snapshot as team_id, e.status, e.progress_percent,
                   e.enrolled_at, e.started_at, e.last_access_at, e.completed_at, e.last_block_id,
                   o.assigned, o.mandatory, o.due_at,
                   coalesce(o.due_at < current_timestamp and e.status <> :completed, false) as overdue,
                   c.lifecycle_status = :courseActive and p.status = :catalogPublished
                     and f.status = :catalogPublished
                     and (r.module_id is null or m.status = :catalogPublished)
                     and (o.assigned or (e.self_enrolled_at is not null
                       and (c.owner_org_id = :actorOrg or exists (
                           select 1 from learning_hub.course_shares s
                           where s.course_id = e.course_id and s.status = :shareActive and s.can_self_enroll
                             and (s.org_id = :actorOrg or (s.include_descendants and s.org_id in (:ancestors)))
                       )))) as can_study
            from learning_hub.course_enrollments e
            join learning_hub.courses c on c.course_id = e.course_id
            join learning_hub.course_revisions r on r.revision_id = e.revision_id
            left join learning_hub.training_programs p on p.program_id = r.program_id
            left join learning_hub.training_fields f on f.field_id = p.field_id
            left join learning_hub.business_modules m on m.module_id = r.module_id
            left join lateral (
                select count(*) > 0 as assigned, coalesce(bool_or(ar.requirement = :mandatory), false) as mandatory,
                       min(ar.effective_due_at) as due_at
                from learning_hub.assignment_recipients ar
                join learning_hub.course_assignments a on a.assignment_id = ar.assignment_id
                where ar.enrollment_id = e.enrollment_id and ar.status = :recipientActive
                  and a.status = :assignmentActive and ar.effective_start_at <= current_timestamp
            ) o on true
            where e.status <> :withdrawn and not exists (
                select 1 from learning_hub.course_enrollments newer
                where newer.course_id = e.course_id and newer.user_id = e.user_id
                  and newer.status <> :withdrawn and newer.cycle_no > e.cycle_no
            )
        ) x
        """;
}

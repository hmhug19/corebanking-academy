package vn.com.coreacademy.learning.repository.custom;

import java.util.List;
import java.util.Map;

import jakarta.persistence.EntityManager;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.annotation.Transactional;
import vn.com.coreacademy.common.persistence.NativeSearchQuery;
import vn.com.coreacademy.learning.dto.LearningDetails.CourseCard;

@Transactional(readOnly = true)
@RequiredArgsConstructor
public class CourseRepositoryCustomImpl implements CourseRepositoryCustom {
    private final EntityManager entityManager;

    /** LATERAL lấy đúng một revision/enrollment; mọi filter và scope dùng chung cho page/count. */
    @Override
    public Page<CourseCard> search(CourseSearch filter, Pageable pageable) {
        NativeSearchQuery sql = new NativeSearchQuery("""
            from learning_hub.courses c
            left join lateral (
                select x.enrollment_id, x.revision_id, x.status, x.progress_percent, x.enrollment_origin
                from learning_hub.course_enrollments x
                where x.course_id = c.course_id and x.user_id = :actor and x.status <> 'withdrawn'
                order by x.cycle_no desc limit 1
            ) e on true
            join lateral (
                select x.revision_id, x.program_id, x.module_id, x.level, x.title, x.default_requirement,
                       x.state, x.update_time
                from learning_hub.course_revisions x
                where x.course_id = c.course_id
                  and (:mode = 'manage' or x.revision_id = coalesce(e.revision_id, c.published_revision_id))
                order by x.revision_no desc limit 1
            ) r on true
            left join learning_hub.training_programs p on p.program_id = r.program_id
            left join learning_hub.training_fields f on f.field_id = p.field_id
            left join lateral (
                select case when count(*) filter (where ar.effective_start_at <= current_timestamp) > 0
                       then min(ar.effective_due_at) filter (where ar.effective_start_at <= current_timestamp)
                       else (array_agg(ar.effective_due_at order by ar.effective_start_at, ar.recipient_id)
                           filter (where ar.effective_start_at > current_timestamp))[1] end as due_at,
                       bool_or(ar.requirement = 'mandatory' and ar.effective_start_at <= current_timestamp) as
                       mandatory,
                       count(*) > 0 as assigned
                from learning_hub.assignment_recipients ar
                join learning_hub.course_assignments a on a.assignment_id = ar.assignment_id and a.status = 'active'
                where ar.enrollment_id = e.enrollment_id and ar.status = 'active'
            ) obligations on true
            left join lateral (
                select count(*) as lesson_count, coalesce(sum(l.estimated_duration_seconds), 0) as duration
                from learning_hub.course_chapters ch
                join learning_hub.course_lessons l on l.chapter_id = ch.chapter_id
                where ch.revision_id = r.revision_id
            ) stats on true
            left join lateral (
                select count(distinct ar.user_id) as assigned_count
                from learning_hub.course_assignments a
                join learning_hub.assignment_recipients ar on ar.assignment_id = a.assignment_id
                where a.course_id = c.course_id and a.status = 'active' and ar.status = 'active'
            ) audience on true
            """);
        sql.bind("actor", filter.actorId());
        sql.bind("mode", filter.mode());
        if ("manage".equals(filter.mode())) {
            applyManagementScope(sql, filter);
        } else {
            sql.and("""
                e.enrollment_id is not null
                or (c.lifecycle_status = 'active' and r.state = 'published'
                    and p.status = 'published' and f.status = 'published' and (
                        c.owner_org_id = :actorOrg
                        or exists (
                            select 1 from learning_hub.course_shares s
                            where s.course_id = c.course_id and s.status = 'active'
                              and (s.org_id = :actorOrg or (s.include_descendants and s.org_id in (:ancestors)))
                        )
                    ))
                """);
            sql.bind("actorOrg", filter.actorOrgId() == null ? -1L : filter.actorOrgId());
            sql.bind("ancestors", filter.ancestorIds().isEmpty() ? List.of(-1L) : filter.ancestorIds());
            if ("mine".equals(filter.mode())) {
                sql.and("e.enrollment_id is not null");
            }
        }
        String term = NativeSearchQuery.containsPattern(filter.q());
        if (term != null) {
            sql.and("lower(c.code) like :term escape '!' or lower(r.title) like :term escape '!'");
            sql.bind("term", term);
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
        if (filter.level() != null) {
            sql.and("r.level = :level");
            sql.bind("level", filter.level());
        }
        if (filter.requirement() != null) {
            sql.and("(case when obligations.mandatory then 'mandatory' else r.default_requirement end) = :req");
            sql.bind("req", filter.requirement());
        }
        if (filter.learningStatus() != null) {
            sql.and("coalesce(e.status, 'not_started') = :learningStatus");
            sql.bind("learningStatus", filter.learningStatus());
        }
        if (filter.publicationState() != null) {
            sql.and("r.state = :state");
            sql.bind("state", filter.publicationState());
        }
        return sql.page(entityManager, """
            select c.course_id, r.revision_id, c.code, r.title, r.program_id, r.module_id, r.level,
                   case when obligations.mandatory then 'mandatory' else r.default_requirement end,
                   r.state, c.lifecycle_status, stats.lesson_count, stats.duration,
                   audience.assigned_count, e.enrollment_id, coalesce(e.status, 'not_started'),
                   coalesce(e.progress_percent, 0), obligations.due_at, coalesce(obligations.assigned, false),
                   coalesce(obligations.due_at < current_timestamp and e.status <> 'completed', false), r.update_time
            """, CourseCard.class, pageable, Map.of("id", "c.course_id", "title", "r.title",
                "updateTime", "r.update_time", "dueAt", "obligations.due_at"));
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

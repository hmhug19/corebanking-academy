package vn.com.coreacademy.assessment.repository;

import java.math.BigDecimal;
import java.time.Instant;
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
import vn.com.coreacademy.assessment.constants.AssessmentCodes.AttemptStatus;
import vn.com.coreacademy.assessment.constants.AssessmentCodes.LearnerStatus;
import vn.com.coreacademy.assessment.constants.AssessmentCodes.ScoreMethod;
import vn.com.coreacademy.assessment.dto.AssessmentViews;
import vn.com.coreacademy.common.persistence.NativeSearchQuery;

/**
 * Truy vấn báo cáo Quiz Engine trên dữ liệu gốc (không lưu số liệu cố định). Phạm vi và bộ lọc do service tính rồi
 * truyền vào; mọi giá trị đều bind parameter, chỉ mệnh đề SQL cố định trong source được ghép.
 *
 * <p>Grain: lượt thi đã chấm (SUBMITTED/AUTO_SUBMITTED) cho kết quả/năng lực/câu hỏi; (đề, học viên) cho tổng quan.
 * Đơn vị/nhóm lấy theo snapshot lúc thi (quiz_attempts.org_id/team_id) để học viên chuyển đơn vị không làm đổi báo
 * cáo quá khứ. Danh sách học viên được giao lấy snapshot của lần học (course_enrollments, đọc chéo module Learning).
 */
@Repository
@RequiredArgsConstructor
public class AssessmentReportRepository {
    private static final List<String> GRADED = List.of(AttemptStatus.SUBMITTED, AttemptStatus.AUTO_SUBMITTED);

    private final EntityManager entityManager;

    /** Phạm vi đã tính (global hoặc org/team) và bộ lọc tùy chọn; orgIds đã gồm cây con. */
    public record Criteria(boolean global, Set<Long> scopeOrgIds, Set<Long> scopeTeamIds, Set<Long> orgIds,
                           Long teamId, Long courseId, Long quizId, String userId, Instant from, Instant to) {
        /** Lượt đang làm chưa có giờ nộp nên không lọc theo khoảng thời gian nộp. */
        Criteria withoutPeriod() {
            return new Criteria(global, scopeOrgIds, scopeTeamIds, orgIds, teamId, courseId, quizId, userId, null,
                null);
        }
    }

    /** Điều kiện lọc lượt thi theo alias; trả SQL bắt đầu bằng "and" và ghi tham số vào params. */
    private static String attemptConditions(String alias, Criteria criteria, Map<String, Object> params) {
        StringBuilder sql = new StringBuilder();
        scope(sql, alias + ".org_id", alias + ".team_id", criteria, params);
        if (criteria.orgIds() != null) {
            sql.append(" and ").append(alias).append(".org_id in (:filterOrgs)");
            params.put("filterOrgs", criteria.orgIds().isEmpty() ? List.of(-1L) : criteria.orgIds());
        }
        if (criteria.teamId() != null) {
            sql.append(" and ").append(alias).append(".team_id = :filterTeam");
            params.put("filterTeam", criteria.teamId());
        }
        if (criteria.quizId() != null) {
            sql.append(" and ").append(alias).append(".quiz_id = :filterQuiz");
            params.put("filterQuiz", criteria.quizId());
        }
        if (criteria.courseId() != null) {
            sql.append(" and ").append(alias).append(".quiz_id in (select z2.quiz_id from learning_hub.quizzes z2 ")
                .append("where z2.course_id = :filterCourse)");
            params.put("filterCourse", criteria.courseId());
        }
        if (criteria.userId() != null) {
            sql.append(" and ").append(alias).append(".user_id = :filterUser");
            params.put("filterUser", criteria.userId());
        }
        if (criteria.from() != null) {
            sql.append(" and ").append(alias).append(".submitted_at >= :filterFrom");
            params.put("filterFrom", criteria.from());
        }
        if (criteria.to() != null) {
            sql.append(" and ").append(alias).append(".submitted_at < :filterTo");
            params.put("filterTo", criteria.to());
        }
        return sql.toString();
    }

    /** Ngoài global, chỉ giữ dòng thuộc đơn vị hoặc nhóm trong phạm vi; phạm vi rỗng không làm rộng quyền. */
    private static void scope(StringBuilder sql, String orgColumn, String teamColumn, Criteria criteria,
                              Map<String, Object> params) {
        if (criteria.global()) {
            return;
        }
        sql.append(" and (").append(orgColumn).append(" in (:scopeOrgs) or ").append(teamColumn)
            .append(" in (:scopeTeams))");
        params.put("scopeOrgs", criteria.scopeOrgIds().isEmpty() ? List.of(-1L) : criteria.scopeOrgIds());
        params.put("scopeTeams", criteria.scopeTeamIds().isEmpty() ? List.of(-1L) : criteria.scopeTeamIds());
    }

    /** CTE (đề, học viên) với điểm cuối/đạt theo score_method của từng đề; lượt mới nhất quyết định đơn vị. */
    private static String learnerCte(Criteria criteria, Map<String, Object> params) {
        params.put("graded", GRADED);
        params.put("last", ScoreMethod.LAST);
        return """
            with g as (
                select a.*, row_number() over (partition by a.quiz_id, a.user_id
                                               order by a.submitted_at desc, a.attempt_id desc) as rn
                from learning_hub.quiz_attempts a
                where a.status in (:graded)
                  {conditions}
            ), l as (
                select g.quiz_id, g.user_id, count(*) as attempts,
                       max(case when g.rn = 1 then g.org_id end) as org_id,
                       max(g.score_percent) as best_score, bool_or(g.passed) as ever_passed,
                       max(case when g.rn = 1 then g.score_percent end) as last_score,
                       bool_or(case when g.rn = 1 then g.passed end) as last_passed
                from g
                group by g.quiz_id, g.user_id
            ), f as (
                select l.*, z.title,
                       case when z.score_method = :last then l.last_score else l.best_score end as final_score,
                       case when z.score_method = :last then l.last_passed else l.ever_passed end as final_passed
                from l
                join learning_hub.quizzes z on z.quiz_id = l.quiz_id
            )
            """.replace("{conditions}", attemptConditions("a", criteria, params));
    }

    public AssessmentViews.ReportTotals totals(Criteria criteria) {
        Map<String, Object> params = new LinkedHashMap<>();
        String sql = learnerCte(criteria, params) + """
            select coalesce(sum(f.attempts), 0), count(*), count(*) filter (where f.final_passed),
                   round(avg(f.final_score), 2)
            from f
            """;
        Object[] row = (Object[]) bind(entityManager.createNativeQuery(sql), params).getSingleResult();
        Map<String, Object> runningParams = new LinkedHashMap<>();
        runningParams.put("running", AttemptStatus.IN_PROGRESS);
        String inProgress = "select count(*) from learning_hub.quiz_attempts a where a.status = :running"
            + attemptConditions("a", criteria.withoutPeriod(), runningParams);
        long running = ((Number) bind(entityManager.createNativeQuery(inProgress), runningParams).getSingleResult())
            .longValue();
        return new AssessmentViews.ReportTotals(((Number) row[0]).longValue(), ((Number) row[1]).longValue(),
            ((Number) row[2]).longValue(), (BigDecimal) row[3], running);
    }

    /** Nhóm theo đề hoặc theo đơn vị của lượt mới nhất; tên đơn vị bổ sung ở service. */
    public List<AssessmentViews.ReportGroupData> groups(Criteria criteria, boolean byQuiz) {
        Map<String, Object> params = new LinkedHashMap<>();
        // {key}/{title} chỉ nhận hai biểu thức cố định trong source, không lấy từ client.
        String sql = learnerCte(criteria, params) + """
            select {key}, {title}, coalesce(sum(f.attempts), 0), count(*), count(*) filter (where f.final_passed),
                   round(avg(f.final_score), 2)
            from f
            group by {key}
            order by {key}
            """.replace("{key}", byQuiz ? "f.quiz_id" : "f.org_id")
            .replace("{title}", byQuiz ? "max(f.title)" : "cast(null as varchar)");
        List<?> rows = bind(entityManager.createNativeQuery(sql), params).getResultList();
        return rows.stream().map(value -> {
            Object[] row = (Object[]) value;
            return new AssessmentViews.ReportGroupData(row[0] == null ? null : ((Number) row[0]).longValue(),
                (String) row[1], ((Number) row[2]).longValue(), ((Number) row[3]).longValue(),
                ((Number) row[4]).longValue(), (BigDecimal) row[5]);
        }).toList();
    }

    public Page<AssessmentViews.ResultRowData> results(Criteria criteria, String status, Boolean passed,
                                                       Pageable page) {
        Map<String, Object> params = new LinkedHashMap<>();
        String conditions = attemptConditions("a", criteria, params);
        NativeSearchQuery sql = new NativeSearchQuery("""
            from learning_hub.quiz_attempts a
            join learning_hub.quizzes z on z.quiz_id = a.quiz_id
            """);
        sql.and("1 = 1" + conditions);
        params.forEach(sql::bind);
        if (status != null) {
            sql.and("a.status = :status");
            sql.bind("status", status);
        }
        if (passed != null) {
            sql.and("a.passed = :passed");
            sql.bind("passed", passed);
        }
        return sql.page(entityManager, """
            select a.attempt_id, a.quiz_id, z.title, z.course_id, a.user_id, a.org_id, a.team_id, a.attempt_no,
                   a.status, a.submit_type, a.started_at, a.submitted_at, a.correct_count, a.total_questions,
                   a.score_percent, a.passed
            """, AssessmentViews.ResultRowData.class, page, Map.of("id", "a.attempt_id",
                "startedAt", "a.started_at", "submittedAt", "a.submitted_at", "scorePercent", "a.score_percent"));
    }

    /**
     * Mỗi học viên có lần học (chưa rút) của khóa chứa đề, kèm tổng hợp lượt của đề; người chưa thi vẫn xuất hiện.
     * Phạm vi xét theo đơn vị/nhóm snapshot của lần học.
     */
    public Page<AssessmentViews.LearnerRowData> learners(long quizId, long courseId, String scoreMethod,
                                                         String withdrawn, Criteria criteria, String learnerStatus,
                                                         Pageable page) {
        NativeSearchQuery sql = new NativeSearchQuery("""
            from (
                select e.user_id, e.enrollment_id, e.org_id_snapshot as org_id, e.team_id_snapshot as team_id,
                       coalesce(s.used, 0) as attempts_used, s.best_score, s.last_score,
                       case when :method = :last then s.last_score else s.best_score end as final_score,
                       coalesce(case when :method = :last then s.last_passed else s.ever_passed end, false) as passed,
                       s.last_submitted_at,
                       case
                           when coalesce(case when :method = :last then s.last_passed else s.ever_passed end, false)
                               then :stPassed
                           when s.graded > 0 then :stFailed
                           when s.running > 0 then :stRunning
                           else :stNotStarted
                       end as learner_status
                from (
                    select distinct on (ce.user_id) ce.user_id, ce.enrollment_id, ce.org_id_snapshot,
                           ce.team_id_snapshot
                    from learning_hub.course_enrollments ce
                    where ce.course_id = :course
                      and ce.status <> :withdrawn
                    order by ce.user_id, ce.cycle_no desc
                ) e
                left join (
                    select a.user_id,
                           count(*) filter (where a.status <> :voided) as used,
                           count(*) filter (where a.status = :running) as running,
                           count(*) filter (where a.status in (:graded)) as graded,
                           max(a.score_percent) filter (where a.status in (:graded)) as best_score,
                           bool_or(a.passed) filter (where a.status in (:graded)) as ever_passed,
                           (array_agg(a.score_percent order by a.submitted_at desc, a.attempt_id desc)
                               filter (where a.status in (:graded)))[1] as last_score,
                           (array_agg(a.passed order by a.submitted_at desc, a.attempt_id desc)
                               filter (where a.status in (:graded)))[1] as last_passed,
                           max(a.submitted_at) filter (where a.status in (:graded)) as last_submitted_at
                    from learning_hub.quiz_attempts a
                    where a.quiz_id = :quiz
                    group by a.user_id
                ) s on s.user_id = e.user_id
            ) r
            """);
        sql.bind("course", courseId);
        sql.bind("quiz", quizId);
        sql.bind("withdrawn", withdrawn);
        sql.bind("method", scoreMethod);
        sql.bind("last", ScoreMethod.LAST);
        sql.bind("voided", AttemptStatus.VOIDED);
        sql.bind("running", AttemptStatus.IN_PROGRESS);
        sql.bind("graded", GRADED);
        sql.bind("stPassed", LearnerStatus.PASSED);
        sql.bind("stFailed", LearnerStatus.FAILED);
        sql.bind("stRunning", LearnerStatus.IN_PROGRESS);
        sql.bind("stNotStarted", LearnerStatus.NOT_STARTED);
        Map<String, Object> params = new LinkedHashMap<>();
        StringBuilder conditions = new StringBuilder("1 = 1");
        scope(conditions, "r.org_id", "r.team_id", criteria, params);
        if (criteria.orgIds() != null) {
            conditions.append(" and r.org_id in (:filterOrgs)");
            params.put("filterOrgs", criteria.orgIds().isEmpty() ? List.of(-1L) : criteria.orgIds());
        }
        if (criteria.teamId() != null) {
            conditions.append(" and r.team_id = :filterTeam");
            params.put("filterTeam", criteria.teamId());
        }
        sql.and(conditions.toString());
        params.forEach(sql::bind);
        if (learnerStatus != null) {
            sql.and("r.learner_status = :learnerStatus");
            sql.bind("learnerStatus", learnerStatus);
        }
        return sql.page(entityManager, """
            select r.user_id, r.enrollment_id, r.org_id, r.team_id, r.attempts_used, r.best_score, r.last_score,
                   r.final_score, r.passed, r.last_submitted_at, r.learner_status
            """, AssessmentViews.LearnerRowData.class, page, Map.of("userId", "r.user_id",
                "finalScore", "r.final_score", "lastSubmittedAt", "r.last_submitted_at",
                // Thứ tự phụ "id" do PageQuery thêm: mỗi học viên một dòng nên user_id là khóa ổn định.
                "id", "r.user_id"));
    }

    /** Lượt cấp thêm còn hiệu lực theo học viên của một đề, để tính số lượt còn lại theo lô. */
    public Map<String, Long> grants(long quizId, List<String> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        List<?> rows = entityManager.createNativeQuery("""
            select g.user_id, sum(g.extra_attempts)
            from learning_hub.quiz_attempt_grants g
            where g.quiz_id = :quiz
              and not g.is_deleted
              and g.user_id in (:users)
            group by g.user_id
            """).setParameter("quiz", quizId).setParameter("users", userIds).getResultList();
        Map<String, Long> grants = new LinkedHashMap<>();
        rows.forEach(value -> {
            Object[] row = (Object[]) value;
            grants.put((String) row[0], ((Number) row[1]).longValue());
        });
        return grants;
    }

    /** Năng lực trên mọi lượt đã chấm trong phạm vi, nhóm theo học viên hoặc đơn vị lúc thi. */
    public Page<AssessmentViews.CompetencyRowData> competency(Criteria criteria, boolean byUser, Pageable page) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("graded", GRADED);
        // {key} chỉ nhận một trong hai biểu thức cố định trong source; {conditions} là SQL do attemptConditions dựng.
        NativeSearchQuery sql = new NativeSearchQuery("""
            from (
                select {key} as group_key, aq.competency_id,
                       count(*) as total_questions,
                       count(*) filter (where ans.is_correct) as correct_count,
                       sum(aq.points) as max_score,
                       coalesce(sum(ans.score_awarded), 0) as score
                from learning_hub.quiz_attempts a
                join learning_hub.attempt_questions aq on aq.attempt_id = a.attempt_id
                left join learning_hub.attempt_answers ans on ans.attempt_question_id = aq.attempt_question_id
                where a.status in (:graded)
                  and aq.competency_id is not null
                  {conditions}
                group by {key}, aq.competency_id
            ) r
            left join learning_hub.competencies c on c.competency_id = r.competency_id
            """.replace("{key}", byUser ? "a.user_id" : "cast(a.org_id as varchar)")
            .replace("{conditions}", attemptConditions("a", criteria, params)));
        params.forEach(sql::bind);
        return sql.page(entityManager, """
            select r.group_key, r.competency_id, c.code, c.name, r.total_questions, r.correct_count, r.max_score,
                   r.score
            """, AssessmentViews.CompetencyRowData.class, page, Map.of("groupKey", "r.group_key",
                "competencyId", "r.competency_id", "id", "r.competency_id"));
    }

    /** Phân tích câu hỏi theo (câu, version): tỷ lệ sai gồm cả bỏ trống, trên lượt đã chấm trong phạm vi. */
    public Page<AssessmentViews.QuestionStatRow> questions(Criteria criteria, Pageable page) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("graded", GRADED);
        NativeSearchQuery sql = new NativeSearchQuery("""
            from (
                select aq.question_id, aq.question_version,
                       max(left(aq.content_snapshot, 200)) as content_preview,
                       count(*) as times_served,
                       count(*) filter (where ans.is_correct) as times_correct,
                       count(*) filter (where ans.answer_id is null
                                        or jsonb_array_length(ans.selected_option_ids) = 0) as times_blank
                from learning_hub.quiz_attempts a
                join learning_hub.attempt_questions aq on aq.attempt_id = a.attempt_id
                left join learning_hub.attempt_answers ans on ans.attempt_question_id = aq.attempt_question_id
                where a.status in (:graded)
                  {conditions}
                group by aq.question_id, aq.question_version
            ) r
            left join learning_hub.questions q on q.question_id = r.question_id
            """.replace("{conditions}", attemptConditions("a", criteria, params)));
        params.forEach(sql::bind);
        return sql.page(entityManager, """
            select r.question_id, r.question_version, q.code, r.content_preview, r.times_served, r.times_correct,
                   r.times_blank, round(100.0 * (r.times_served - r.times_correct) / r.times_served, 2)
            """, AssessmentViews.QuestionStatRow.class, page, Map.of("id", "r.question_id",
                "questionId", "r.question_id", "timesServed", "r.times_served",
                "wrongPercent", "(r.times_served - r.times_correct) * 1.0 / r.times_served"));
    }

    private static Query bind(Query query, Map<String, Object> params) {
        params.forEach(query::setParameter);
        return query;
    }
}

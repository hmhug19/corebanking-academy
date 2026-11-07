package vn.com.coreacademy.learning.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import jakarta.persistence.EntityManager;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * Huy hiệu: định nghĩa và luật, huy hiệu mỗi người đã nhận, và các số đo mà luật cần (khóa hoàn thành, chương trình, đạt
 * 100% ngay lần đầu, khóa giao xong trước hạn). Mọi số đo nhận một người hoặc null cho mọi người, để cùng một truy vấn
 * phục vụ cả sự kiện lẫn trao bù khi tạo huy hiệu. Dùng SQL thuần.
 */
@Repository
@RequiredArgsConstructor
public class BadgeRepository {
    private final EntityManager entityManager;

    /** Một huy hiệu và luật của nó; paramN hoặc paramProgramId tùy loại luật. */
    public record Definition(long badgeId, String code, String name, String description, String icon, String ruleType,
                             Integer paramN, Long paramProgramId, String status, int sortNo) {
    }

    private static final String COLUMNS = "badge_id, code, name, description, icon, rule_type, param_n,"
        + " param_program_id, status, sort_no";

    /** Chỉ các ca dùng chung trong một giao dịch cần đẩy thay đổi chưa ghi xuống trước khi đọc bằng SQL thuần. */
    public void flush() {
        entityManager.flush();
    }

    public long countDefinitions() {
        return ((Number) entityManager.createNativeQuery("select count(*) from learning_hub.badge_definitions")
            .getSingleResult()).longValue();
    }

    /** Tạo huy hiệu; trả badge_id, hoặc rỗng nếu mã đã có. */
    public Optional<Long> insertDefinition(String code, String name, String description, String icon, String ruleType,
                                           Integer paramN, Long paramProgramId, int sortNo, String createdBy) {
        List<?> rows = entityManager.createNativeQuery("""
            insert into learning_hub.badge_definitions (code, name, description, icon, rule_type, param_n,
                param_program_id, sort_no, created_by)
            values (:code, :name, :description, :icon, :ruleType, :paramN, :programId, :sortNo, :createdBy)
            on conflict (code) do nothing
            returning badge_id
            """)
            .setParameter("code", code).setParameter("name", name).setParameter("description", description)
            .setParameter("icon", icon).setParameter("ruleType", ruleType).setParameter("paramN", paramN)
            .setParameter("programId", paramProgramId).setParameter("sortNo", sortNo)
            .setParameter("createdBy", createdBy).getResultList();
        return rows.stream().map(row -> ((Number) row).longValue()).findFirst();
    }

    public List<Definition> definitions() {
        return map(entityManager.createNativeQuery("select " + COLUMNS
            + " from learning_hub.badge_definitions order by sort_no, badge_id").getResultList());
    }

    public Optional<Definition> definition(long badgeId) {
        return map(entityManager.createNativeQuery("select " + COLUMNS
            + " from learning_hub.badge_definitions where badge_id = :id").setParameter("id", badgeId)
            .getResultList()).stream().findFirst();
    }

    /** Một huy hiệu cho quản trị: số người đã nhận và tiến độ trao bù nền. */
    public record AdminRow(Definition definition, long holders, String backfillStatus, int backfillTotal,
                           int backfillDone) {
    }

    /** Mọi huy hiệu (kể cả đã ngừng dùng) theo thứ tự hiển thị, kèm số người nhận và tiến độ trao bù. */
    public List<AdminRow> adminRows() {
        List<?> rows = entityManager.createNativeQuery("""
            select d.badge_id, (select count(*) from learning_hub.user_badges u where u.badge_id = d.badge_id),
                   d.backfill_status, d.backfill_total, d.backfill_done
            from learning_hub.badge_definitions d order by d.sort_no, d.badge_id
            """).getResultList();
        Map<Long, Definition> definitions = new HashMap<>();
        definitions().forEach(definition -> definitions.put(definition.badgeId(), definition));
        return rows.stream().map(Object[].class::cast).map(row -> new AdminRow(
            definitions.get(((Number) row[0]).longValue()), ((Number) row[1]).longValue(), (String) row[2],
            ((Number) row[3]).intValue(), ((Number) row[4]).intValue())).toList();
    }

    public Optional<AdminRow> adminRow(long badgeId) {
        return adminRows().stream().filter(row -> row.definition().badgeId() == badgeId).findFirst();
    }

    public long holders(long badgeId) {
        return ((Number) entityManager.createNativeQuery("select count(*) from learning_hub.user_badges"
            + " where badge_id = :id").setParameter("id", badgeId).getSingleResult()).longValue();
    }

    public int nextSortNo() {
        return ((Number) entityManager.createNativeQuery("select coalesce(max(sort_no), -1) + 1"
            + " from learning_hub.badge_definitions").getSingleResult()).intValue();
    }

    public boolean programExists(long programId) {
        return ((Number) entityManager.createNativeQuery("select count(*) from learning_hub.training_programs"
            + " where program_id = :id").setParameter("id", programId).getSingleResult()).longValue() > 0;
    }

    /** Sửa mô tả và luật của một huy hiệu; mã và trạng thái giữ nguyên. */
    public void updateDefinition(long badgeId, String name, String description, String icon, String ruleType,
                                 Integer paramN, Long paramProgramId) {
        entityManager.createNativeQuery("""
            update learning_hub.badge_definitions
               set name = :name, description = :description, icon = :icon, rule_type = :ruleType,
                   param_n = :paramN, param_program_id = :programId, update_time = now()
             where badge_id = :id
            """)
            .setParameter("name", name).setParameter("description", description).setParameter("icon", icon)
            .setParameter("ruleType", ruleType).setParameter("paramN", paramN)
            .setParameter("programId", paramProgramId).setParameter("id", badgeId).executeUpdate();
    }

    public void setStatus(long badgeId, String status) {
        entityManager.createNativeQuery("update learning_hub.badge_definitions set status = :status,"
            + " update_time = now() where badge_id = :id").setParameter("status", status)
            .setParameter("id", badgeId).executeUpdate();
    }

    /** Ghi tiến độ trao bù nền. */
    public void setBackfill(long badgeId, String status, int total, int done) {
        entityManager.createNativeQuery("update learning_hub.badge_definitions set backfill_status = :status,"
            + " backfill_total = :total, backfill_done = :done where badge_id = :id")
            .setParameter("status", status).setParameter("total", total).setParameter("done", done)
            .setParameter("id", badgeId).executeUpdate();
    }

    public void failBackfill(long badgeId) {
        entityManager.createNativeQuery("update learning_hub.badge_definitions set backfill_status = 'FAILED'"
            + " where badge_id = :id").setParameter("id", badgeId).executeUpdate();
    }

    /** Huy hiệu đang trao bù dở (ứng dụng bị dừng giữa chừng) để chạy lại. */
    public List<Long> runningBackfills() {
        return entityManager.createNativeQuery("select badge_id from learning_hub.badge_definitions"
            + " where backfill_status = 'RUNNING' order by badge_id").getResultList().stream()
            .map(row -> ((Number) row).longValue()).toList();
    }

    /** Huy hiệu còn dùng, thuộc các loại luật đã cho, mà người này chưa nhận. */
    public List<Definition> activeNotHeld(String userId, Collection<String> ruleTypes) {
        return map(entityManager.createNativeQuery("select " + COLUMNS + """
             from learning_hub.badge_definitions d
            where d.status = 'active' and d.rule_type in (:types)
              and not exists (select 1 from learning_hub.user_badges u
                              where u.user_id = :user and u.badge_id = d.badge_id)
            order by d.sort_no, d.badge_id
            """).setParameter("types", ruleTypes).setParameter("user", userId).getResultList());
    }

    /** Trao huy hiệu; trả true nếu đây là lần đầu (đã nhận thì không ghi thêm). */
    public boolean award(String userId, long badgeId, String source) {
        return entityManager.createNativeQuery("""
            insert into learning_hub.user_badges (user_id, badge_id, source) values (:user, :badge, :source)
            on conflict (user_id, badge_id) do nothing
            """).setParameter("user", userId).setParameter("badge", badgeId).setParameter("source", source)
            .executeUpdate() == 1;
    }

    /** Trao huy hiệu cho nhiều người một lúc; trả số người mới nhận. */
    public int awardAll(long badgeId, Collection<String> userIds, String source) {
        int awarded = 0;
        for (String userId : userIds) {
            if (award(userId, badgeId, source)) {
                awarded++;
            }
        }
        return awarded;
    }

    /** Huy hiệu người này đã nhận: badge_id → lúc nhận. */
    public Map<Long, Instant> earned(String userId) {
        List<?> rows = entityManager.createNativeQuery("select badge_id, earned_at from learning_hub.user_badges"
            + " where user_id = :user").setParameter("user", userId).getResultList();
        Map<Long, Instant> result = new HashMap<>();
        for (Object row : rows) {
            Object[] columns = (Object[]) row;
            result.put(((Number) columns[0]).longValue(), instant(columns[1]));
        }
        return result;
    }

    /** Chương trình đang hoạt động: program_id → {mã, tên}. */
    public List<Object[]> activePrograms() {
        return entityManager.createNativeQuery("select program_id, code, name from learning_hub.training_programs"
            + " where status = 'published' order by sort_no, program_id").getResultList().stream()
            .map(Object[].class::cast).toList();
    }

    public Map<Long, String> programNames(Collection<Long> programIds) {
        if (programIds.isEmpty()) {
            return Map.of();
        }
        List<?> rows = entityManager.createNativeQuery("select program_id, name from learning_hub.training_programs"
            + " where program_id in (:ids)").setParameter("ids", programIds).getResultList();
        Map<Long, String> result = new HashMap<>();
        for (Object row : rows) {
            Object[] columns = (Object[]) row;
            result.put(((Number) columns[0]).longValue(), (String) columns[1]);
        }
        return result;
    }

    /** Số khóa khác nhau đã hoàn thành của mỗi người (hoặc của một người). */
    public Map<String, Integer> completedCourses(String userId) {
        return counts("""
            select e.user_id, count(distinct e.course_id)
            from learning_hub.course_enrollments e
            where e.status = 'completed' and (cast(:user as varchar) is null or e.user_id = :user)
            group by e.user_id
            """, userId);
    }

    /** Người đã hoàn thành mọi khóa đã xuất bản của chương trình (chương trình phải có ít nhất một khóa). */
    public Set<String> programCompleters(long programId, String userId) {
        List<?> rows = entityManager.createNativeQuery("""
            with pc as (
                select c.course_id from learning_hub.courses c
                join learning_hub.course_revisions r on r.revision_id = c.published_revision_id
                where r.program_id = :program
            )
            select e.user_id
            from learning_hub.course_enrollments e
            join pc on pc.course_id = e.course_id
            where e.status = 'completed' and (cast(:user as varchar) is null or e.user_id = :user)
            group by e.user_id
            having count(distinct e.course_id) = (select count(*) from pc)
            """).setParameter("program", programId).setParameter("user", userId).getResultList();
        Set<String> result = new HashSet<>();
        rows.forEach(row -> result.add((String) row));
        return result;
    }

    /** Số khóa của chương trình đã xuất bản và số khóa người này đã hoàn thành: {xong, tổng}. */
    public int[] programProgress(long programId, String userId) {
        Object[] row = (Object[]) entityManager.createNativeQuery("""
            select count(*),
                   count(*) filter (where exists (select 1 from learning_hub.course_enrollments e
                                                  where e.user_id = :user and e.course_id = c.course_id
                                                    and e.status = 'completed'))
            from learning_hub.courses c
            join learning_hub.course_revisions r on r.revision_id = c.published_revision_id
            where r.program_id = :program
            """).setParameter("program", programId).setParameter("user", userId).getSingleResult();
        return new int[] {((Number) row[1]).intValue(), ((Number) row[0]).intValue()};
    }

    /** Số đề khác nhau đạt từ 100% ngay lượt đầu tiên của mỗi người. */
    public Map<String, Integer> firstTryPerfect(String userId) {
        return counts("""
            select a.user_id, count(distinct a.quiz_id)
            from learning_hub.quiz_attempts a
            join learning_hub.quizzes z on z.quiz_id = a.quiz_id
            where a.status in ('SUBMITTED', 'AUTO_SUBMITTED') and a.graded_at is not null and a.passed = true
              and a.attempt_no = 1 and a.score_percent >= 100 and z.status = 'PUBLISHED' and not z.is_deleted
              and (cast(:user as varchar) is null or a.user_id = :user)
            group by a.user_id
            """, userId);
    }

    /** Số đợt giao mà người nhận đã hoàn thành khóa trước hoặc đúng hạn; đợt không có hạn hay đã thu hồi không tính. */
    public Map<String, Integer> beforeDeadline(String userId) {
        return counts("""
            select r.user_id, count(distinct r.assignment_id)
            from learning_hub.assignment_recipients r
            join learning_hub.course_enrollments e on e.enrollment_id = r.enrollment_id
            where r.status <> 'revoked' and r.effective_due_at is not null and e.status = 'completed'
              and e.completed_at is not null and e.completed_at <= r.effective_due_at
              and (cast(:user as varchar) is null or r.user_id = :user)
            group by r.user_id
            """, userId);
    }

    /** Mọi người có dấu vết học (khóa đã nhận hoặc lượt thi), để tính chuỗi khi trao bù. */
    public List<String> learners() {
        return entityManager.createNativeQuery("""
            select user_id from learning_hub.course_enrollments
            union
            select user_id from learning_hub.quiz_attempts
            """).getResultList().stream().map(String.class::cast).toList();
    }

    private Map<String, Integer> counts(String sql, String userId) {
        List<?> rows = entityManager.createNativeQuery(sql).setParameter("user", userId).getResultList();
        Map<String, Integer> result = new HashMap<>();
        for (Object row : rows) {
            Object[] columns = (Object[]) row;
            result.put((String) columns[0], ((Number) columns[1]).intValue());
        }
        return result;
    }

    private static List<Definition> map(List<?> rows) {
        return rows.stream().map(Object[].class::cast).map(row -> new Definition(((Number) row[0]).longValue(),
            (String) row[1], (String) row[2], (String) row[3], (String) row[4], (String) row[5],
            row[6] == null ? null : ((Number) row[6]).intValue(), row[7] == null ? null : ((Number) row[7]).longValue(),
            (String) row[8], ((Number) row[9]).intValue())).toList();
    }

    private static Instant instant(Object value) {
        if (value instanceof Instant instant) {
            return instant;
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant();
        }
        return ((OffsetDateTime) value).toInstant();
    }
}

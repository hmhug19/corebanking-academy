package vn.com.coreacademy.learning.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.EntityManager;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * Read queries behind the certificates: a certificate is a completed enrollment cycle, so there is no table of its
 * own. Only rows of the given user with status completed and completed_at set count; the title is the one of the
 * revision the cycle was learned on. Paging is done in the database.
 */
@Repository
@RequiredArgsConstructor
public class LearningCertificateRepository {
    private static final String SELECT = """
        select e.enrollment_id, e.course_id, c.code, r.title, e.cycle_no, e.revision_id, e.user_id, e.completed_at
        from learning_hub.course_enrollments e
        join learning_hub.courses c on c.course_id = e.course_id
        join learning_hub.course_revisions r on r.revision_id = e.revision_id
        where e.user_id = :userId and e.status = 'completed' and e.completed_at is not null
        """;

    private final EntityManager entityManager;

    /** One completed cycle joined with its course code and the title of the revision that was learned. */
    public record CertificateRow(Long enrollmentId, Long courseId, String courseCode, String courseTitle,
                                 Integer cycleNo, Long revisionId, String userId, Instant completedAt) {
    }

    public long count(String userId) {
        return ((Number) entityManager.createNativeQuery("""
            select count(*) from learning_hub.course_enrollments
            where user_id = :userId and status = 'completed' and completed_at is not null
            """).setParameter("userId", userId).getSingleResult()).longValue();
    }

    /** Newest completion first, then the highest enrollment id. */
    public List<CertificateRow> page(String userId, int offset, int limit) {
        return entityManager.createNativeQuery(SELECT + " order by e.completed_at desc, e.enrollment_id desc")
            .setParameter("userId", userId).setFirstResult(offset).setMaxResults(limit).getResultList().stream()
            .map(row -> row((Object[]) row)).toList();
    }

    /** Empty when the enrollment is not the user's, is not completed (withdrawn included) or does not exist. */
    public Optional<CertificateRow> find(String userId, Long enrollmentId) {
        return entityManager.createNativeQuery(SELECT + " and e.enrollment_id = :id")
            .setParameter("userId", userId).setParameter("id", enrollmentId).getResultList().stream()
            .map(row -> row((Object[]) row)).findFirst();
    }

    private static CertificateRow row(Object[] v) {
        return new CertificateRow(((Number) v[0]).longValue(), ((Number) v[1]).longValue(), (String) v[2],
            (String) v[3], ((Number) v[4]).intValue(), ((Number) v[5]).longValue(), (String) v[6], instant(v[7]));
    }

    /** Native timestamptz comes back as one of three types depending on the JDBC setup. */
    private static Instant instant(Object value) {
        if (value instanceof Instant instant) {
            return instant;
        }
        if (value instanceof OffsetDateTime offset) {
            return offset.toInstant();
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant();
        }
        throw new IllegalStateException("Unexpected timestamp type " + value.getClass().getName());
    }
}

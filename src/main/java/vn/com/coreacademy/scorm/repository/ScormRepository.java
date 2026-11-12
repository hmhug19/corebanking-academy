package vn.com.coreacademy.scorm.repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import vn.com.coreacademy.common.exception.BusinessException;
import vn.com.coreacademy.common.exception.ResponseCode;

/** SQL ownership and runtime writes stay behind this repository; no physical foreign keys are introduced. */
@Repository
@RequiredArgsConstructor
public class ScormRepository {
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final ObjectMapper json;

    public record StoredFile(String key, long size, String mime) { }
    public record PackageRow(long id, long orgId, String uploadedBy, String title, String originalName,
                             String launchPath, Map<String, StoredFile> files, List<String> warnings, long sizeBytes,
                             int fileCount, BigDecimal mastery, boolean published, long version, Instant createdAt) { }
    public record Registration(long id, long packageId, String userId, boolean preview, Map<String, String> cmi,
                               BigDecimal totalSeconds, BigDecimal sessionSeconds, Instant expiresAt,
                               long authVersion, long sequence, String payloadHash, Instant updatedAt) { }

    public long insert(long orgId, String userId, String name, long size, PackageData data) {
        return jdbc.queryForObject("""
            INSERT INTO learning_hub.scorm_packages
              (owner_org_id, uploaded_by, title, original_name, launch_path, files, warnings, size_bytes,
               file_count, mastery_score)
            VALUES (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?) RETURNING package_id
            """, Long.class, orgId, userId, data.title(), name, data.launch(), encode(data.files()),
            encode(data.warnings()), size, data.files().size(), data.mastery());
    }

    public record PackageData(String title, String launch, Map<String, StoredFile> files,
                                         List<String> warnings, BigDecimal mastery) { }

    public PackageRow require(long id) {
        return jdbc.query("SELECT * FROM learning_hub.scorm_packages WHERE package_id = ?", packageMapper(), id)
            .stream().findFirst().orElseThrow(() -> new BusinessException(ResponseCode.RESOURCE_NOT_FOUND));
    }

    public record Filter(boolean global, Set<Long> managedOrgs, String ownerId, long actorOrgId,
                         Set<Long> learnerOrgs, String query) { }
    private static final String FILTER = """
        WHERE (:global OR owner_org_id IN (:managed) OR (uploaded_by = :owner AND owner_org_id = :actorOrg)
          OR (published AND owner_org_id IN (:visible))) AND lower(title) LIKE :query ESCAPE '\\'
        """;

    public List<PackageRow> list(Filter filter, int page, int size) {
        var params = parameters(filter);
        params.put("offset", (long) page * size);
        params.put("size", size);
        return named.query("SELECT * FROM learning_hub.scorm_packages " + FILTER
            + " ORDER BY package_id DESC LIMIT :size OFFSET :offset", params, packageMapper());
    }

    public long count(Filter filter) {
        return named.queryForObject("SELECT count(*) FROM learning_hub.scorm_packages " + FILTER,
            parameters(filter), Long.class);
    }

    private Map<String, Object> parameters(Filter f) {
        Map<String, Object> params = new java.util.HashMap<>();
        params.put("global", f.global());
        params.put("managed", f.managedOrgs().isEmpty() ? Set.of(-1L) : f.managedOrgs());
        params.put("owner", f.ownerId());
        params.put("actorOrg", f.actorOrgId());
        params.put("visible", f.learnerOrgs().isEmpty() ? Set.of(-1L) : f.learnerOrgs());
        params.put("query", "%" + f.query().toLowerCase(java.util.Locale.ROOT).replace("\\", "\\\\")
            .replace("%", "\\%").replace("_", "\\_") + "%");
        return params;
    }

    public boolean publish(long id, long version) {
        return jdbc.update("""
            UPDATE learning_hub.scorm_packages SET published = true, row_version = row_version + 1
            WHERE package_id = ? AND row_version = ?
            """, id, version) == 1;
    }

    public void ensureRegistration(long packageId, String userId, boolean preview) {
        jdbc.update("""
            INSERT INTO learning_hub.scorm_registrations (package_id, user_id, preview) VALUES (?, ?, ?)
            ON CONFLICT (package_id, user_id, preview) DO NOTHING
            """, packageId, userId, preview);
    }

    public Registration registration(long packageId, String userId, boolean preview, boolean locked) {
        return jdbc.query("""
            SELECT * FROM learning_hub.scorm_registrations WHERE package_id = ? AND user_id = ? AND preview = ?
            """ + (locked ? " FOR UPDATE" : ""), registrationMapper(), packageId, userId, preview)
            .stream().findFirst().orElse(null);
    }

    public Map<Long, Registration> learnerRegistrations(List<Long> ids, String userId) {
        if (ids.isEmpty()) return Map.of();
        return named.query("""
            SELECT * FROM learning_hub.scorm_registrations
            WHERE package_id IN (:ids) AND user_id = :user AND NOT preview
            """, Map.of("ids", ids, "user", userId), registrationMapper()).stream()
            .collect(java.util.stream.Collectors.toMap(Registration::packageId, java.util.function.Function.identity()));
    }

    public Registration token(String hash, boolean locked) {
        return jdbc.query("SELECT * FROM learning_hub.scorm_registrations WHERE launch_token_hash = ?"
            + (locked ? " FOR UPDATE" : ""), registrationMapper(), hash).stream().findFirst().orElse(null);
    }

    public void launch(Registration r, String hash, Instant expires, long authVersion) {
        jdbc.update("""
            UPDATE learning_hub.scorm_registrations SET launch_token_hash = ?, launch_expires_at = ?,
              authorization_version = ?, last_sequence = 0, last_payload_hash = null,
              total_seconds = total_seconds + session_seconds, session_seconds = 0 WHERE registration_id = ?
            """, hash, java.sql.Timestamp.from(expires), authVersion, r.id());
    }

    public void runtime(Registration r, Map<String, String> cmi, BigDecimal seconds, long sequence, String hash) {
        jdbc.update("""
            UPDATE learning_hub.scorm_registrations SET cmi = ?::jsonb, session_seconds = ?, last_sequence = ?,
              last_payload_hash = ?, update_time = now() WHERE registration_id = ?
            """, encode(cmi), seconds, sequence, hash, r.id());
    }

    public String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (java.io.IOException exception) { throw new IllegalStateException("Cannot encode SCORM metadata", exception); }
    }

    private <T> T decode(String value, TypeReference<T> type) {
        try { return json.readValue(value, type); }
        catch (java.io.IOException exception) { throw new IllegalStateException("Invalid stored SCORM metadata", exception); }
    }

    private RowMapper<PackageRow> packageMapper() {
        return (r, index) -> new PackageRow(r.getLong("package_id"), r.getLong("owner_org_id"),
            r.getString("uploaded_by"), r.getString("title"), r.getString("original_name"), r.getString("launch_path"),
            decode(r.getString("files"), new TypeReference<>() { }),
            decode(r.getString("warnings"), new TypeReference<>() { }), r.getLong("size_bytes"),
            r.getInt("file_count"), r.getBigDecimal("mastery_score"), r.getBoolean("published"),
            r.getLong("row_version"), time(r, "create_time"));
    }

    private RowMapper<Registration> registrationMapper() {
        return (r, index) -> new Registration(r.getLong("registration_id"), r.getLong("package_id"),
            r.getString("user_id"), r.getBoolean("preview"), decode(r.getString("cmi"), new TypeReference<>() { }),
            r.getBigDecimal("total_seconds"), r.getBigDecimal("session_seconds"), time(r, "launch_expires_at"),
            r.getLong("authorization_version"), r.getLong("last_sequence"), r.getString("last_payload_hash"),
            time(r, "update_time"));
    }

    private static Instant time(ResultSet result, String column) throws SQLException {
        var time = result.getTimestamp(column);
        return time == null ? null : time.toInstant();
    }
}

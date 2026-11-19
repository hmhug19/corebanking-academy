package vn.com.coreacademy.assistant.external.oauth;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** The OAuth clients that registered themselves (RFC 7591): their id, the name they gave and their redirect URIs. */
@Repository
@RequiredArgsConstructor
public class OAuthClientRepository {
    public record ClientRow(String clientId, String name, List<String> redirectUris, OffsetDateTime createTime) {
    }

    private static final TypeReference<List<String>> URIS = new TypeReference<>() {
    };

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper json;

    public ClientRow insert(String clientId, String name, List<String> redirectUris) {
        return jdbc.queryForObject("""
            INSERT INTO learning_hub.oauth_client (client_id, client_name, redirect_uris)
            VALUES (:id, :name, CAST(:uris AS jsonb))
            RETURNING client_id, client_name, redirect_uris::text AS redirect_uris, create_time
            """, new MapSqlParameterSource().addValue("id", clientId).addValue("name", name)
            .addValue("uris", write(redirectUris)), (rs, row) -> new ClientRow(rs.getString("client_id"),
            rs.getString("client_name"), read(rs.getString("redirect_uris")),
            rs.getObject("create_time", OffsetDateTime.class)));
    }

    public Optional<ClientRow> find(String clientId) {
        return jdbc.query("""
            SELECT client_id, client_name, redirect_uris::text AS redirect_uris, create_time
              FROM learning_hub.oauth_client
             WHERE client_id = :id
            """, Map.of("id", clientId), (rs, row) -> new ClientRow(rs.getString("client_id"),
            rs.getString("client_name"), read(rs.getString("redirect_uris")),
            rs.getObject("create_time", OffsetDateTime.class))).stream().findFirst();
    }

    private String write(List<String> uris) {
        try {
            return json.writeValueAsString(uris);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private List<String> read(String uris) {
        try {
            return json.readValue(uris, URIS);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }
}

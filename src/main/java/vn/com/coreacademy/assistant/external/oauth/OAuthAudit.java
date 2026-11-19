package vn.com.coreacademy.assistant.external.oauth;

import java.util.LinkedHashMap;
import java.util.Map;

import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;
import vn.com.coreacademy.audit.service.StandaloneAuditService;

/**
 * Audit records of the OAuth of a Trợ lý ngoài: grants, refusals and revocations. Details hold ids, the client id
 * and a reason only, never a token, a code or a verifier.
 */
@Log4j2
@Component
@RequiredArgsConstructor
public class OAuthAudit {
    public static final String ACTION = "EXTERNAL_ASSISTANT_OAUTH";
    static final String ENTITY = "external_assistant_connection";

    /** What happened. */
    public enum Event {
        REGISTRATION_REFUSED, AUTHORIZE_REFUSED, ANSWER_REFUSED, GRANTED, DENIED, REVOKED, REFRESH_REFUSED,
        REFRESH_REUSED
    }

    private final StandaloneAuditService audit;

    /** Records the event; a failure to record is logged and never fails the call. */
    public void record(Event event, String userId, Long connectionId, String clientId, String reason) {
        try {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("event", event.name());
            details.put("clientId", clientId);
            details.put("connectionId", connectionId);
            if (reason != null) {
                details.put("reason", reason);
            }
            audit.record(userId, ACTION, ENTITY, connectionId == null ? null : connectionId.toString(), details);
        } catch (RuntimeException exception) {
            log.error("External assistant OAuth event not audited event={} exceptionType={}", event,
                exception.getClass().getName());
        }
    }
}

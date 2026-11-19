package vn.com.coreacademy.assistant.external;

import java.time.Duration;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

/**
 * Trợ lý ngoài (ADR 0004). Off by default: until the bank approves sending documents to an outside model the
 * connection endpoints and {@code /api/v1/mcp} answer 404. {@code appBaseUrl} is the web app the editor link of a
 * drafting session points to. The limits hold per connection ({@code callsPerMinute}), per request on the MCP endpoint
 * ({@code maxBody}) and per user ({@code maxActiveSessions} open sessions an external assistant drives).
 * {@code lastUsedInterval} is how stale a connection's "lần dùng cuối" may get before a call writes it again.
 * {@code oauthRegistrationsPerMinute} is how many OAuth clients one address may register in a minute (ADR 0005).
 */
@Validated
@ConfigurationProperties("app.assistant.external")
public record ExternalAssistantProperties(@DefaultValue("false") boolean enabled,
                                          @DefaultValue("http://localhost:4200") @NotBlank String appBaseUrl,
                                          @DefaultValue("60") @Min(1) int callsPerMinute,
                                          @DefaultValue("256KB") @NotNull DataSize maxBody,
                                          @DefaultValue("3") @Min(1) int maxActiveSessions,
                                          @DefaultValue("1m") @NotNull Duration lastUsedInterval,
                                          @DefaultValue("10") @Min(1) int oauthRegistrationsPerMinute) {
    /** The web app's origin without a trailing slash: the OAuth issuer, and what the MCP URL starts with. */
    public String origin() {
        String base = appBaseUrl.strip();
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    /** Where a client finds the MCP endpoint's OAuth metadata (RFC 9728); a 401 from the endpoint names it. */
    /** The MCP endpoint on the web origin: the URL users paste and the only resource OAuth grants. */
    public String mcpUrl() {
        return origin() + ExternalMcpSecurityConfig.MCP_PATH;
    }

    public String resourceMetadataUrl() {
        return origin() + "/.well-known/oauth-protected-resource" + ExternalMcpSecurityConfig.MCP_PATH;
    }
}

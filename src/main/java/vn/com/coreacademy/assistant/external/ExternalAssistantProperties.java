package vn.com.coreacademy.assistant.external;

import java.time.Duration;
import java.util.List;

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
 * OAuth (ADR 0005): {@code oauthRegistrationsPerMinute} is how many clients one address may register in a minute and
 * {@code oauthRegistrationsPerMinuteTotal} how many all addresses together may; {@code oauthAuthorizationsPerMinute}
 * is how many authorization requests one address may start in a minute. The address is the peer's, or the
 * {@code X-Real-IP} the reverse proxy sets when the peer is one of {@code oauthTrustedProxies} (CIDR).
 */
@Validated
@ConfigurationProperties("app.assistant.external")
public record ExternalAssistantProperties(@DefaultValue("false") boolean enabled,
                                          @DefaultValue("http://localhost:4200") @NotBlank String appBaseUrl,
                                          @DefaultValue("60") @Min(1) int callsPerMinute,
                                          @DefaultValue("256KB") @NotNull DataSize maxBody,
                                          @DefaultValue("3") @Min(1) int maxActiveSessions,
                                          @DefaultValue("1m") @NotNull Duration lastUsedInterval,
                                          @DefaultValue("10") @Min(1) int oauthRegistrationsPerMinute,
                                          @DefaultValue("100") @Min(1) int oauthRegistrationsPerMinuteTotal,
                                          @DefaultValue("30") @Min(1) int oauthAuthorizationsPerMinute,
                                          @DefaultValue({"127.0.0.0/8", "::1/128", "10.0.0.0/8", "172.16.0.0/12",
                                              "192.168.0.0/16"}) @NotNull List<String> oauthTrustedProxies) {
    /** The web app's origin without a trailing slash: the OAuth issuer, and what the MCP URL starts with. */
    public String origin() {
        String base = appBaseUrl.strip();
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    /** The MCP endpoint on the web origin: the URL users paste and the only resource OAuth grants. */
    public String mcpUrl() {
        return origin() + ExternalMcpSecurityConfig.MCP_PATH;
    }

    /** Where a client finds the MCP endpoint's OAuth metadata (RFC 9728); a 401 from the endpoint names it. */
    public String resourceMetadataUrl() {
        return origin() + "/.well-known/oauth-protected-resource" + ExternalMcpSecurityConfig.MCP_PATH;
    }
}

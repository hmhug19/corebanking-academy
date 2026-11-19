package vn.com.coreacademy.assistant.external.oauth;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The redirect URI rules and the registration limit, which the ITs cannot reach at their edges. */
class OAuthRulesTest {
    @Test
    void onlyClaudeCallbacksAndLoopbackUrisMayBeRegistered() {
        assertThat(OAuthRedirects.allowed("https://claude.ai/api/mcp/auth_callback")).isTrue();
        assertThat(OAuthRedirects.allowed("https://claude.com/api/mcp/auth_callback")).isTrue();
        assertThat(OAuthRedirects.allowed("http://localhost:53682/callback")).isTrue();
        assertThat(OAuthRedirects.allowed("http://127.0.0.1/callback")).isTrue();
        assertThat(OAuthRedirects.allowed("http://[::1]:8080/")).isTrue();

        assertThat(OAuthRedirects.allowed("https://claude.ai/api/mcp/auth_callback?x=1")).isFalse();
        assertThat(OAuthRedirects.allowed("http://localhost.evil.example/cb")).isFalse();
        assertThat(OAuthRedirects.allowed("http://localhost:1/cb#fragment")).isFalse();
        assertThat(OAuthRedirects.allowed("https://127.0.0.1/cb")).isFalse();
        assertThat(OAuthRedirects.allowed(null)).isFalse();
        assertThat(OAuthRedirects.allowed("http://localhost:1/" + "a".repeat(600))).isFalse();
    }

    @Test
    void aLoopbackRedirectMatchesOnAnyPortButOnlyTheSameHostAndPath() {
        assertThat(OAuthRedirects.matches("http://localhost:1000/cb", "http://localhost:2000/cb")).isTrue();
        assertThat(OAuthRedirects.matches("http://localhost/cb", "http://localhost:2000/cb")).isTrue();
        assertThat(OAuthRedirects.matches("http://localhost:1000/cb", "http://127.0.0.1:1000/cb")).isFalse();
        assertThat(OAuthRedirects.matches("http://localhost:1000/cb", "http://localhost:1000/other")).isFalse();
        assertThat(OAuthRedirects.matches("https://claude.ai/api/mcp/auth_callback",
            "https://claude.com/api/mcp/auth_callback")).isFalse();
        assertThat(OAuthRedirects.matches("https://claude.ai/api/mcp/auth_callback",
            "https://claude.ai/api/mcp/auth_callback")).isTrue();
    }

    @Test
    void anAddressRegistersAtMostTheLimitInAMinute() {
        Clock clock = Clock.fixed(Instant.parse("2026-11-19T10:00:30Z"), ZoneOffset.UTC);
        PerMinuteLimiter limiter = new PerMinuteLimiter(2, clock);

        assertThat(limiter.admit("10.0.0.1")).isTrue();
        assertThat(limiter.admit("10.0.0.1")).isTrue();
        assertThat(limiter.admit("10.0.0.1")).isFalse();
        assertThat(limiter.admit("10.0.0.2")).isTrue();

        PerMinuteLimiter nextMinute = new PerMinuteLimiter(2,
            Clock.fixed(Instant.parse("2026-11-19T10:01:00Z"), ZoneOffset.UTC));
        assertThat(nextMinute.admit("10.0.0.1")).isTrue();
    }

    @Test
    void aClientNameLosesControlAndBidiCharactersAndIsCutByCodePoints() {
        assertThat(OAuthClientService.name(registration("Claude‮ (official)\nline")))
            .isEqualTo("Claude (official) line");
        assertThat(OAuthClientService.name(registration("  ​ "))).isEqualTo(OAuthCodes.UNNAMED_CLIENT);
        assertThat(OAuthClientService.name(registration(null))).isEqualTo(OAuthCodes.UNNAMED_CLIENT);

        String cut = OAuthClientService.name(registration("a".repeat(99) + "😀😀"));
        assertThat(cut.codePointCount(0, cut.length())).isEqualTo(OAuthCodes.MAX_CLIENT_NAME);
        assertThat(cut).endsWith("😀");
    }

    private static OAuthRegistrationController.Registration registration(String name) {
        return new OAuthRegistrationController.Registration(name, null, null, null, null);
    }
}

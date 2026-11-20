package vn.com.coreacademy.assistant.external;

import org.springframework.test.context.TestPropertySource;
import vn.com.coreacademy.assessment.support.AssessmentIntegrationTest;

/**
 * The Trợ lý ngoài switched on. Every IT of the feature extends this one and adds no context settings of its own, so
 * they share one application context (and one connection pool) instead of one each.
 */
@TestPropertySource(properties = {"app.assistant.external.enabled=true",
    "app.assistant.external.app-base-url=https://lms.example",
    // Every OAuth IT registers and authorizes from the same address; the limits are pinned by OAuthRulesTest.
    "app.assistant.external.oauth-registrations-per-minute=1000",
    "app.assistant.external.oauth-registrations-per-minute-total=1000",
    "app.assistant.external.oauth-authorizations-per-minute=1000"})
abstract class ExternalAssistantIT extends AssessmentIntegrationTest {
}

package vn.com.coreacademy.assistant;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import vn.com.coreacademy.assistant.agent.AssistantGate;
import vn.com.coreacademy.identity.dto.MeResponse;
import vn.com.coreacademy.identity.service.AuthService;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AssistantGateTest {
    private final AuthService auth = mock(AuthService.class);
    private final AssistantGate gate = new AssistantGate(auth);

    private void callerHas(String... functions) {
        when(auth.me()).thenReturn(new MeResponse(null, List.of(), List.of(functions), List.of(), List.of(), Map.of()));
    }

    @Test
    void aCallerWithTheAssistantFunctionPasses() {
        callerHas("AI_ASSISTANT", "PROFILE");

        assertThatCode(gate::requireAccess).doesNotThrowAnyException();
    }

    @Test
    void aCallerWithoutItIsDeniedEvenWithEveryOtherFunction() {
        callerHas("PROFILE", "KNOWLEDGE_HUB", "USER_MANAGEMENT");

        assertThatThrownBy(gate::requireAccess).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void aUserOnATemporaryPasswordHasNoFunctionsAndIsDenied() {
        callerHas();

        assertThatThrownBy(gate::requireAccess).isInstanceOf(AccessDeniedException.class);
    }
}

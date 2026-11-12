package vn.com.coreacademy.assistant;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import vn.com.coreacademy.assistant.action.PendingActionRepository;
import vn.com.coreacademy.assistant.action.PendingActionStore;
import vn.com.coreacademy.assistant.config.AssistantProperties;
import vn.com.coreacademy.common.exception.BusinessException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The outcome text a cancelled card keeps, so reopening the conversation does not show an empty paragraph. */
class PendingActionStoreTest {
    private final PendingActionRepository actions = mock(PendingActionRepository.class);
    private final PendingActionStore store = new PendingActionStore(
        new AssistantProperties(4, 20, Duration.ofMinutes(10), 4), actions, new ObjectMapper(),
        Clock.fixed(Instant.parse("2026-11-11T10:00:00Z"), ZoneOffset.UTC));

    @Test
    void cancellingStoresTheOutcomeMessage() {
        when(actions.answer(anyString(), anyString(), anyString(), any())).thenReturn(1);

        store.cancel("a1", "000004");

        verify(actions).setMessage("a1", PendingActionStore.CANCELLED_MESSAGE);
        org.assertj.core.api.Assertions.assertThat(PendingActionStore.CANCELLED_MESSAGE).isEqualTo("Đã huỷ thay đổi");
    }

    @Test
    void aMissingOrForeignActionStoresNothing() {
        when(actions.answer(anyString(), anyString(), anyString(), any())).thenReturn(0);

        assertThatThrownBy(() -> store.cancel("a1", "000005")).isInstanceOf(BusinessException.class);
        verify(actions, never()).setMessage(anyString(), anyString());
    }
}

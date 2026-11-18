package vn.com.coreacademy.assistant.drafting;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import vn.com.coreacademy.assistant.conversation.ConversationViews.DraftingCard;
import vn.com.coreacademy.assistant.conversation.DraftingCards;
import vn.com.coreacademy.assistant.drafting.CourseDraftingRepository.StepState;

/** The chat's "Đã chốt" cards read live from the sessions, so each shows what became of its step. */
@Component
@RequiredArgsConstructor
public class DraftingCardStates implements DraftingCards {
    private final CourseDraftingRepository drafting;

    @Override
    public Map<Key, DraftingCard> cards(String ownerId, Collection<Key> keys) {
        Map<Key, DraftingCard> cards = new HashMap<>();
        if (keys.isEmpty()) {
            return cards;
        }
        for (StepState state : drafting.stepStates(ownerId, keys.stream().map(Key::sessionId).distinct().toList())) {
            Key key = new Key(state.sessionId(), state.stepNo());
            if (keys.contains(key)) {
                cards.put(key, new DraftingCard(state.sessionId(), state.stepNo(), state.state(), state.courseId()));
            }
        }

        return cards;
    }
}

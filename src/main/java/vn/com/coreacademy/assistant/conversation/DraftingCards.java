package vn.com.coreacademy.assistant.conversation;

import java.util.Collection;
import java.util.Map;

import vn.com.coreacademy.assistant.conversation.ConversationViews.DraftingCard;

/**
 * The live state of the "Đã chốt" cards of drafting step messages. A stored turn keeps only which session and step it
 * is about; reading the conversation asks the session for what became of that step, so a reopened conversation shows
 * each card with its real outcome. Implemented by the drafting package.
 */
public interface DraftingCards {
    /** No drafting at all: every card is missing. */
    DraftingCards NONE = (ownerId, keys) -> Map.of();

    record Key(long sessionId, int step) {
    }

    /** The card of each key whose session belongs to {@code ownerId} and has reached that step. */
    Map<Key, DraftingCard> cards(String ownerId, Collection<Key> keys);
}

package vn.com.coreacademy.assistant.conversation;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface FeedbackRepository extends JpaRepository<AssistantFeedback, Long> {
    List<AssistantFeedback> findByTurnIdIn(Collection<Long> turnIds);

    /** Shared rows only; an empty {@code reason} or {@code skill} means no filter. */
    @Query("select f from AssistantFeedback f where f.shared = true "
        + "and (:reason = '' or f.reasonCode = :reason) and (:skill = '' or f.sharedSkill = :skill)")
    Page<AssistantFeedback> findShared(@Param("reason") String reason, @Param("skill") String skill,
                                       Pageable pageable);

    @Transactional
    @Modifying
    @Query("delete from AssistantFeedback f where f.turnId in "
        + "(select t.id from AssistantTurn t where t.conversationId = :conversationId)")
    int deleteOfConversation(@Param("conversationId") Long conversationId);

    @Transactional
    @Modifying
    @Query("delete from AssistantFeedback f where f.ownerId = :ownerId")
    int deleteAllByOwner(@Param("ownerId") String ownerId);

    @Transactional
    @Modifying
    @Query("delete from AssistantFeedback f where f.turnId in (select t.id from AssistantTurn t where "
        + "t.conversationId in (select c.id from AssistantConversation c where c.lastActivityTime < :cutoff))")
    int deleteOfConversationsInactiveBefore(@Param("cutoff") Instant cutoff);
}

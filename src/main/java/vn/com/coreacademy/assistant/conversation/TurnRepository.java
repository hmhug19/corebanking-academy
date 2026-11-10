package vn.com.coreacademy.assistant.conversation;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TurnRepository extends JpaRepository<AssistantTurn, Long> {
    List<AssistantTurn> findByConversationIdOrderByIdAsc(Long conversationId);

    /** Newest first, so a caller can stop reading once its budget is spent. */
    List<AssistantTurn> findByConversationIdAndStatusOrderByIdDesc(Long conversationId, String status,
                                                                  Pageable pageable);

    @Modifying
    @Query("delete from AssistantTurn t where t.conversationId = :conversationId")
    int deleteByConversation(@Param("conversationId") Long conversationId);

    @Modifying
    @Query("delete from AssistantTurn t where t.conversationId in "
        + "(select c.id from AssistantConversation c where c.ownerId = :ownerId)")
    int deleteAllByOwner(@Param("ownerId") String ownerId);

    @Modifying
    @Query("delete from AssistantTurn t where t.conversationId in "
        + "(select c.id from AssistantConversation c where c.lastActivityTime < :cutoff)")
    int deleteInactiveBefore(@Param("cutoff") Instant cutoff);
}

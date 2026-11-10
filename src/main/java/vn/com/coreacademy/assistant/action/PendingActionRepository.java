package vn.com.coreacademy.assistant.action;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface PendingActionRepository extends JpaRepository<AssistantPendingAction, String> {
    /**
     * The one place an action is answered: the row moves out of PENDING only when it is the owner's and still live, in a
     * single statement, so two concurrent answers cannot both win. Zero rows means missing, foreign, expired or already
     * answered, which the caller reports alike.
     */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("update AssistantPendingAction a set a.status = :to, a.resolvedTime = :now where a.id = :id "
        + "and a.ownerId = :owner and a.status = 'PENDING' and a.expiresAt > :now")
    int answer(@Param("id") String id, @Param("owner") String owner, @Param("to") String to,
               @Param("now") Instant now);

    @Transactional
    @Modifying
    @Query("update AssistantPendingAction a set a.resultMessage = :message where a.id = :id")
    int setMessage(@Param("id") String id, @Param("message") String message);

    @Transactional
    @Modifying
    @Query("update AssistantPendingAction a set a.status = 'FAILED', a.resultMessage = :message where a.id = :id")
    int fail(@Param("id") String id, @Param("message") String message);

    /** The owner's live actions, oldest first, so the oldest can be dropped when there are too many. */
    @Query("select a from AssistantPendingAction a where a.ownerId = :owner and a.status = 'PENDING' "
        + "and a.expiresAt > :now order by a.createTime asc, a.id asc")
    List<AssistantPendingAction> live(@Param("owner") String owner, @Param("now") Instant now, Pageable pageable);

    @Query("select count(a) from AssistantPendingAction a where a.ownerId = :owner and a.status = 'PENDING' "
        + "and a.expiresAt > :now")
    long countLive(@Param("owner") String owner, @Param("now") Instant now);

    @Transactional
    @Modifying
    @Query("delete from AssistantPendingAction a where a.createTime < :cutoff")
    int deleteCreatedBefore(@Param("cutoff") Instant cutoff);

    @Transactional
    @Modifying
    @Query("delete from AssistantPendingAction a where a.id in "
        + "(select t.actionId from AssistantTurn t where t.conversationId = :conversationId and t.actionId is not null)")
    int deleteOfConversation(@Param("conversationId") Long conversationId);

    @Transactional
    @Modifying
    @Query("delete from AssistantPendingAction a where a.id in "
        + "(select t.actionId from AssistantTurn t where t.actionId is not null and t.conversationId in "
        + "(select c.id from AssistantConversation c where c.lastActivityTime < :cutoff))")
    int deleteOfConversationsInactiveBefore(@Param("cutoff") Instant cutoff);

    @Transactional
    @Modifying
    @Query("delete from AssistantPendingAction a where a.ownerId = :owner")
    int deleteAllByOwner(@Param("owner") String owner);
}

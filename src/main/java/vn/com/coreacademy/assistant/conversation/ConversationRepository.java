package vn.com.coreacademy.assistant.conversation;

import java.time.Instant;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ConversationRepository extends JpaRepository<AssistantConversation, Long> {
    Optional<AssistantConversation> findByIdAndOwnerId(Long id, String ownerId);

    boolean existsByIdAndOwnerId(Long id, String ownerId);

    Page<AssistantConversation> findByOwnerId(String ownerId, Pageable pageable);

    /** {@code q} arrives with its LIKE wildcards already escaped by {@link ConversationService}. */
    @Query("select c from AssistantConversation c where c.ownerId = :ownerId "
        + "and lower(c.title) like lower(concat('%', :q, '%')) escape '!'")
    Page<AssistantConversation> searchByTitle(@Param("ownerId") String ownerId, @Param("q") String q,
                                              Pageable pageable);

    @Modifying
    @Query("delete from AssistantConversation c where c.ownerId = :ownerId")
    int deleteAllByOwner(@Param("ownerId") String ownerId);

    @Modifying
    @Query("delete from AssistantConversation c where c.lastActivityTime < :cutoff")
    int deleteInactiveBefore(@Param("cutoff") Instant cutoff);
}

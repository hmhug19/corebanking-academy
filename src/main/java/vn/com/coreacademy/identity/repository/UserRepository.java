package vn.com.coreacademy.identity.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.com.coreacademy.identity.entity.UserAccount;
import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<UserAccount, String> {
    Optional<UserAccount> findByUsername(String username);

    @Query("select u from UserAccount u where u.id = :identifier or u.username = :normalizedUsername")
    List<UserAccount> findLoginCandidates(@Param("identifier") String identifier,
                                          @Param("normalizedUsername") String normalizedUsername);
}

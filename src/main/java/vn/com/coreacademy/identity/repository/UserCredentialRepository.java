package vn.com.coreacademy.identity.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import vn.com.coreacademy.identity.entity.UserCredential;

public interface UserCredentialRepository extends JpaRepository<UserCredential, String> {
}

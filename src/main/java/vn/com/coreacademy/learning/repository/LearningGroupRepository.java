package vn.com.coreacademy.learning.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import vn.com.coreacademy.learning.entity.LearningGroup;

public interface LearningGroupRepository extends JpaRepository<LearningGroup, Long> {
    Page<LearningGroup> findByOwnerOrgId(Long ownerOrgId, Pageable pageable);

    boolean existsByOwnerOrgIdAndCodeIgnoreCase(Long ownerOrgId, String code);
}

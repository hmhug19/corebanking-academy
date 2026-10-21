package vn.com.coreacademy.learning.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import vn.com.coreacademy.learning.entity.TrainingField;

public interface TrainingFieldRepository extends JpaRepository<TrainingField, Long> {
    Page<TrainingField> findByOwnerOrgId(Long ownerOrgId, Pageable pageable);

    boolean existsByCodeIgnoreCaseAndIdNot(String code, Long id);
}

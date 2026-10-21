package vn.com.coreacademy.learning.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import vn.com.coreacademy.learning.entity.BusinessModule;

public interface BusinessModuleRepository extends JpaRepository<BusinessModule, Long> {
    Page<BusinessModule> findByFieldId(Long fieldId, Pageable pageable);

    boolean existsByFieldIdAndCodeIgnoreCaseAndIdNot(Long fieldId, String code, Long id);
}

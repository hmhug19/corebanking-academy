package vn.com.coreacademy.learning.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import vn.com.coreacademy.learning.entity.CourseAssignment;

public interface CourseAssignmentRepository extends JpaRepository<CourseAssignment, Long> {
    Page<CourseAssignment> findByCourseIdAndAssigningOrgId(Long courseId, Long assigningOrgId, Pageable pageable);

    Page<CourseAssignment> findByAssigningOrgId(Long assigningOrgId, Pageable pageable);
}

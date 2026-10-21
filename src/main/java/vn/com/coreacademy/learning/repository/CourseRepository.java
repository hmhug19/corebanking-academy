package vn.com.coreacademy.learning.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import vn.com.coreacademy.learning.entity.Course;
import vn.com.coreacademy.learning.repository.custom.CourseRepositoryCustom;

public interface CourseRepository extends JpaRepository<Course, Long>, CourseRepositoryCustom {
}

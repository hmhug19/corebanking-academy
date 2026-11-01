package vn.com.coreacademy.learning.repository;

import java.time.LocalDate;

import org.springframework.data.jpa.repository.JpaRepository;
import vn.com.coreacademy.learning.entity.LearningActivityDay;

public interface LearningActivityDayRepository extends JpaRepository<LearningActivityDay, Long> {
    boolean existsByUserIdAndActivityDate(String userId, LocalDate activityDate);
}

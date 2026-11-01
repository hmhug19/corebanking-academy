package vn.com.coreacademy.learning.dto;

import java.time.Instant;

import vn.com.coreacademy.assessment.dto.AssessmentDashboard;
import vn.com.coreacademy.learning.dto.LearningAnalytics.Home;

public record LearningDashboard(Instant dataAsOf, String timeZone, Home learning,
                                AssessmentDashboard.Summary assessment) {
}

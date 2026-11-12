package vn.com.coreacademy.learning.service;

/**
 * Asks whoever generates content into a draft revision whether its chapter and lesson structure may change now. The
 * AI course generation says no from the moment the author approves the outline until the job finishes, stops or fails;
 * the Learning module does not know about jobs, only about this question.
 */
public interface CourseStructureGuard {
    boolean structureLocked(long revisionId);
}

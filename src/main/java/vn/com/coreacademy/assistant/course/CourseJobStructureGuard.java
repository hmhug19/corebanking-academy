package vn.com.coreacademy.assistant.course;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import vn.com.coreacademy.learning.service.CourseStructureGuard;

/** Tells the course editor that chapters and lessons are locked while a job is writing lessons into the draft. */
@Component
@RequiredArgsConstructor
class CourseJobStructureGuard implements CourseStructureGuard {
    private final CourseJobRepository jobs;

    @Override
    public boolean structureLocked(long revisionId) {
        return jobs.structureLocked(revisionId);
    }
}

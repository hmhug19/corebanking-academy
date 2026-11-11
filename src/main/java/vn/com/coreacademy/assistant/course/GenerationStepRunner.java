package vn.com.coreacademy.assistant.course;

import java.util.List;

import vn.com.coreacademy.assistant.course.CourseJobRepository.StepRow;

/**
 * Does the model work of one step. The worker claims the step, calls this and stores what it returns; the runner never
 * touches the step's or the job's status itself.
 */
public interface GenerationStepRunner {
    /** A step to add after this one, for the same job. */
    record NextStep(String kind, String itemKey) {
    }

    /**
     * The step's result: its JSON output, a hash of what it was made from, the steps to add, the job's progress, and the
     * status to move the job to ({@code null}: keep running, or complete when no step is left).
     */
    record Result(String outputJson, String inputHash, List<NextStep> next, int progress, String jobStatus) {
        public static Result of(String outputJson, String inputHash, int progress) {
            return new Result(outputJson, inputHash, List.of(), progress, null);
        }
    }

    /** A failure whose text is fit to show the author as the job's error. */
    class GenerationException extends RuntimeException {
        public GenerationException(String message) {
            super(message);
        }

        public GenerationException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    Result run(StepRow step);
}

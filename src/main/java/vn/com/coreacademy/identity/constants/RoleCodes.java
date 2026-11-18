package vn.com.coreacademy.identity.constants;

import java.util.Set;

public final class RoleCodes {
    public static final String LEARNER = "LEARNER";
    public static final String INSTRUCTOR = "INSTRUCTOR";
    public static final String ADMIN = "ADMIN";
    public static final String SUPER_ADMIN = "SUPER_ADMIN";
    public static final String REPORT_VIEWER = "REPORT_VIEWER";
    /** The roles that manage courses, so may create one with the assistant (Soạn cùng trợ lý, old AI jobs). */
    public static final Set<String> COURSE_MANAGERS = Set.of(SUPER_ADMIN, ADMIN, INSTRUCTOR);

    private RoleCodes() {
    }
}

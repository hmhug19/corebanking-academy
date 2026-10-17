package vn.com.coreacademy.identity.repository.projection;

public interface TeamMemberPreview {
    Long getTeamId();

    String getUserId();

    String getFullName();
}

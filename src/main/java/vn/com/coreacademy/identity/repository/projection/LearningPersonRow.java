package vn.com.coreacademy.identity.repository.projection;

/** Chỉ đọc các cột cần resolve giao học; không tải credential của danh sách người nhận. */
public interface LearningPersonRow {
    String getId();

    String getFullName();

    Long getOrgId();

    Long getTeamId();
}

package vn.com.coreacademy.organization.dto;

import java.util.List;

public record TeamInfo(Long id, Long orgId, String code, String name, String description, String status,
                       long memberCount, List<MemberPreview> memberPreview) {
    public record MemberPreview(String userId, String fullName) {
    }
}

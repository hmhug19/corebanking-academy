package vn.com.coreacademy.organization.dto;

/** Một node của cấp cây đang tải; hasChildren chỉ tính node con trong phạm vi được phép xem. */
public record OrganizationTreeNode(Long id, Long parentId, String orgType, String code, String name,
                                   String status, int sortNo, boolean hasChildren) {
}

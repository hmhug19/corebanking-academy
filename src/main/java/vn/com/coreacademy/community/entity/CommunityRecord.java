package vn.com.coreacademy.community.entity;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Version;

import lombok.Getter;
import lombok.Setter;
import vn.com.coreacademy.common.persistence.UpdatedEntity;

/** Audit do service gán từ caller đã xác thực; version bảo vệ cập nhật đồng thời. */
@Getter
@Setter
@MappedSuperclass
public abstract class CommunityRecord extends UpdatedEntity {
    @Column(name = "created_by", nullable = false, length = 20)
    private String createdBy;

    @Column(name = "updated_by", nullable = false, length = 20)
    private String updatedBy;

    @Version
    @Column(name = "row_version", nullable = false)
    private Long rowVersion;
}

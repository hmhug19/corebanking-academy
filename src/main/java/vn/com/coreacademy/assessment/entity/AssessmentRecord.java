package vn.com.coreacademy.assessment.entity;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Version;

import lombok.Getter;
import lombok.Setter;
import vn.com.coreacademy.common.persistence.UpdatedEntity;

/** Metadata tác nhân và optimistic lock cho dữ liệu quản trị của Quiz Engine. */
@Getter
@Setter
@MappedSuperclass
public abstract class AssessmentRecord extends UpdatedEntity {
    @Column(name = "created_by", length = 20, nullable = false)
    private String createdBy;

    @Column(name = "updated_by", length = 20, nullable = false)
    private String updatedBy;

    @Version
    @Column(name = "row_version", nullable = false)
    private Long rowVersion;
}

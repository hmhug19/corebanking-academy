package vn.com.coreacademy.learning.entity;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Version;

import lombok.Getter;
import lombok.Setter;
import vn.com.coreacademy.common.persistence.UpdatedEntity;

/** Metadata ghi nhận tác nhân và version chung, không chứa logic quyền hoặc quan hệ đối tượng. */
@Getter
@Setter
@MappedSuperclass
public abstract class LearningRecord extends UpdatedEntity {
    @Column(name = "created_by", length = 20, nullable = false)
    private String createdBy;

    @Column(name = "updated_by", length = 20, nullable = false)
    private String updatedBy;

    @Version
    @Column(name = "row_version", nullable = false)
    private Long rowVersion;
}

package vn.com.coreacademy.common.persistence;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;

import lombok.Getter;

/**
 * update_time được cập nhật qua lifecycle JPA; query update native/bulk phải tự cập nhật timestamp này.
 */
@Getter
@MappedSuperclass
public abstract class UpdatedEntity extends CreatedEntity {
    @Column(name = "update_time", nullable = false)
    private Instant updateTime = Instant.now();

    @PrePersist
    @PreUpdate
    protected void touchUpdateTime() {
        updateTime = Instant.now();
    }
}

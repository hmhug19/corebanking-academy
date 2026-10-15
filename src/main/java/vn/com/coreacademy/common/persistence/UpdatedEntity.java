package vn.com.coreacademy.common.persistence;

import lombok.Getter;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import java.time.Instant;

@Getter
@MappedSuperclass
public abstract class UpdatedEntity extends CreatedEntity {
    @Column(name = "update_time", nullable = false)
    private Instant updateTime = Instant.now();

    @PrePersist
    @PreUpdate
    protected void touchUpdateTime() { updateTime = Instant.now(); }
}

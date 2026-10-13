package vn.com.coreacademy.common.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;
import java.time.Instant;

@MappedSuperclass
public abstract class UpdatedEntity extends CreatedEntity {
    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Column(name = "update_time", nullable = false, insertable = false, updatable = false)
    private Instant updateTime;

    public Instant getUpdateTime() { return updateTime; }
}

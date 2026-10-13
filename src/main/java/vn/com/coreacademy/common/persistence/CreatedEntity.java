package vn.com.coreacademy.common.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;
import java.time.Instant;

@MappedSuperclass
public abstract class CreatedEntity {
    @Generated(event = EventType.INSERT)
    @Column(name = "create_time", nullable = false, insertable = false, updatable = false)
    private Instant createTime;

    public Instant getCreateTime() { return createTime; }
}

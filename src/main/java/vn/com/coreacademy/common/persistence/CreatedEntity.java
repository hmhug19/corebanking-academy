package vn.com.coreacademy.common.persistence;

import lombok.Getter;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;
import java.time.Instant;

@Getter
@MappedSuperclass
public abstract class CreatedEntity {
    @Generated(event = EventType.INSERT)
    @Column(name = "create_time", nullable = false, insertable = false, updatable = false)
    private Instant createTime;
}

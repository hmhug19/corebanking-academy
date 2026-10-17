package vn.com.coreacademy.common.persistence;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;

import lombok.Getter;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

/**
 * create_time do default database sinh; Hibernate đọc lại sau insert, không ghi đè từ ứng dụng.
 */
@Getter
@MappedSuperclass
public abstract class CreatedEntity {
    @Generated(event = EventType.INSERT)
    @Column(name = "create_time", nullable = false, insertable = false, updatable = false)
    private Instant createTime;
}

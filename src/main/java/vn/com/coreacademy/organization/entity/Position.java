package vn.com.coreacademy.organization.entity;

import jakarta.persistence.*;
import vn.com.coreacademy.common.persistence.UpdatedEntity;


@Entity
@Table(name = "positions", schema = "learning_hub")
public class Position extends UpdatedEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "pos_id", nullable = false)
    private Long id;

    @Column(name = "code", length = 30, nullable = false, unique = true)
    private String code;

    @Column(name = "name", length = 255, nullable = false)
    private String name;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

}

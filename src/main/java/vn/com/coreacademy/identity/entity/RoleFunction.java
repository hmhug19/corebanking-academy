package vn.com.coreacademy.identity.entity;

import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;

import jakarta.persistence.*;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "role_functions", schema = "learning_hub")
public class RoleFunction {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;
    @Column(name = "role_id", nullable = false)
    private Long roleId;
    @Column(name = "function_id", nullable = false)
    private Long functionId;
    @Column(name = "created_at", nullable = false)
    private java.time.Instant createdAt = java.time.Instant.now();
}

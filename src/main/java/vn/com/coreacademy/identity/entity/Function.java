package vn.com.coreacademy.identity.entity;

import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;

import jakarta.persistence.*;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "functions", schema = "learning_hub")
public class Function {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "function_id", nullable = false)
    private Long id;
    @Column(name = "code", length = 80, nullable = false, unique = true)
    private String code;
    @Column(name = "name", length = 255, nullable = false)
    private String name;
    @Column(name = "route_pattern", length = 255)
    private String routePattern;
    @Column(name = "parent_function_id")
    private Long parentFunctionId;
    @Column(name = "sort_no", nullable = false)
    private int sortNo = 0;
    @Column(name = "active", nullable = false)
    private boolean active = true;
}

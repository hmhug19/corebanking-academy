package vn.com.coreacademy.organization.entity;

import jakarta.persistence.Column;
import jakarta.persistence.ConstraintMode;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import vn.com.coreacademy.common.persistence.CreatedEntity;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "teams", schema = "learning_hub")
public class Team extends CreatedEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "team_id", nullable = false)
    private Long id;

    @Column(name = "org_id", nullable = false)
    private Long orgId;

    @Column(name = "code", length = 50)
    private String code;

    @Column(name = "name", length = 255, nullable = false)
    private String name;

    @Column(name = "description", columnDefinition = "text")
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    private String description;

    @Column(name = "status", length = 20, nullable = false)
    private String status = "active";

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
        name = "org_id",
        referencedColumnName = "org_id",
        insertable = false,
        updatable = false,
        foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT)
    )
    private Organization organization;
}

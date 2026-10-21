package vn.com.coreacademy.learning.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Phân hệ nghiệp vụ dùng phân loại khóa học. Các cột ID liên kết là scalar, không tạo khóa ngoại vật lý. */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "business_modules", schema = "learning_hub")
public class BusinessModule extends LearningRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "module_id", nullable = false)
    private Long id;

    @Column(name = "field_id", nullable = false)
    private Long fieldId;

    @Column(name = "code", length = 50, nullable = false)
    private String code;

    @Column(name = "name", length = 255, nullable = false)
    private String name;

    @Column(name = "status", length = 20, nullable = false)
    private String status;

    @Column(name = "sort_no", nullable = false)
    private Integer sortNo;
}

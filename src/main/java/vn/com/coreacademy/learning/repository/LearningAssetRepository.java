package vn.com.coreacademy.learning.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import vn.com.coreacademy.learning.entity.LearningAsset;
import vn.com.coreacademy.learning.repository.custom.LearningAssetRepositoryCustom;

public interface LearningAssetRepository extends JpaRepository<LearningAsset, Long>, LearningAssetRepositoryCustom {
}

package vn.com.coreacademy.learning.repository.custom;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import vn.com.coreacademy.learning.dto.MediaViews;
import vn.com.coreacademy.learning.service.LearningAccessPolicy.SearchScope;

public interface LearningAssetRepositoryCustom {
    record Filter(String actorId, Long orgId, Long teamId, SearchScope scope,
                  String q, String mediaType, String status) {
    }

    Page<MediaViews.Asset> search(Filter filter, Pageable page);
}

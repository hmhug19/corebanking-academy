package vn.com.coreacademy.learning.service;

import java.util.Set;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.com.coreacademy.common.dto.PageResponse;
import vn.com.coreacademy.common.exception.BusinessException;
import vn.com.coreacademy.common.web.PageQuery;
import vn.com.coreacademy.identity.service.LearningIdentityService;
import vn.com.coreacademy.learning.dto.LearningDetails.CourseCard;
import vn.com.coreacademy.learning.repository.CourseRepository;
import vn.com.coreacademy.learning.repository.custom.CourseRepositoryCustom.CourseSearch;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class LearningSearchService {
    private final CourseRepository repository;
    private final LearningAccessPolicy access;
    private final LearningIdentityService identity;

    public PageResponse<CourseCard> search(String mode, String q, Long fieldId, Long programId, Long moduleId,
                                           String level, String requirement, String learningStatus, String state,
                                           int page, int size, String sort) {
        // Bước 1: scope xuất phát từ grant đang hiệu lực; client chỉ được gửi filter nghiệp vụ.
        var actor = access.actor();
        if (!"manage".equals(mode)) {
            access.requireLearner(actor);
        }
        allowed("level", level, Set.of("basic", "intermediate", "advanced"));
        allowed("requirement", requirement, Set.of("mandatory", "optional"));
        allowed("learningStatus", learningStatus, Set.of("not_started", "in_progress", "completed"));
        allowed("state", state, Set.of("draft", "published"));
        var scope = access.searchScope(actor);
        var filter = new CourseSearch(mode, actor.person().id(), actor.person().orgId(),
            identity.activeAncestorIds(actor.person().orgId()), scope, q, fieldId, programId, moduleId, level,
            requirement, learningStatus, state);

        // Bước 2: repository lọc quyền và đếm trên grain khóa học trước phân trang; không lọc trang trong memory.
        return PageResponse.from(repository.search(filter,
            PageQuery.of(page, size, sort, Set.of("id", "title", "updateTime", "dueAt"))));
    }

    private void allowed(String field, String value, Set<String> options) {
        if (value != null && !options.contains(value)) {
            throw BusinessException.validation(field, "INVALID_VALUE", "Giá trị bộ lọc chưa hợp lệ.");
        }
    }
}

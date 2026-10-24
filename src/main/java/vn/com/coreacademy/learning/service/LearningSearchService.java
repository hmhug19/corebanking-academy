package vn.com.coreacademy.learning.service;

import java.util.Set;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.com.coreacademy.common.dto.PageResponse;
import vn.com.coreacademy.common.exception.BusinessException;
import vn.com.coreacademy.common.web.PageQuery;
import vn.com.coreacademy.identity.service.LearningIdentityService;
import vn.com.coreacademy.learning.constants.LearningCodes.Level;
import vn.com.coreacademy.learning.constants.LearningCodes.ProgressStatus;
import vn.com.coreacademy.learning.constants.LearningCodes.Requirement;
import vn.com.coreacademy.learning.constants.LearningCodes.RevisionState;
import vn.com.coreacademy.learning.constants.LearningCodes.SearchMode;
import vn.com.coreacademy.learning.constants.LearningLimits;
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
        return search(mode, q, fieldId, programId, moduleId, level, requirement, learningStatus, state,
            page, size, sort, null);
    }

    public PageResponse<CourseCard> search(String mode, String q, Long fieldId, Long programId, Long moduleId,
                                           String level, String requirement, String learningStatus, String state,
                                           int page, int size, String sort, Boolean overdue) {
        var pageable = PageQuery.of(page, size, sort, Set.of("id", "title", "updateTime", "dueAt", "progressPercent",
            "startedAt", "lastAccessAt", "completedAt"));
        return PageResponse.from(query(mode, q, fieldId, programId, moduleId, level, requirement, learningStatus,
            state, overdue, pageable));
    }

    public Page<CourseCard> export(String q, Long fieldId, Long programId, Long moduleId, String level,
                                   String requirement, String learningStatus, String state, Boolean overdue,
                                   String sort) {
        var order = PageQuery.of(0, 1, sort, Set.of("id", "title", "updateTime", "dueAt", "progressPercent",
            "startedAt", "lastAccessAt", "completedAt")).getSort();
        return query(SearchMode.MINE, q, fieldId, programId, moduleId, level, requirement, learningStatus, state,
            overdue, PageRequest.of(0, LearningLimits.MAX_EXPORT_ROWS + 1, order));
    }

    private Page<CourseCard> query(String mode, String q, Long fieldId, Long programId, Long moduleId, String level,
                                   String requirement, String learningStatus, String state, Boolean overdue,
                                   Pageable pageable) {
        // Bước 1: scope xuất phát từ grant đang hiệu lực; client chỉ được gửi filter nghiệp vụ.
        var actor = access.actor();
        if (!SearchMode.MANAGE.equals(mode)) {
            access.requireLearner(actor);
        }
        allowed("level", level, Level.VALUES);
        allowed("requirement", requirement, Requirement.VALUES);
        allowed("learningStatus", learningStatus, ProgressStatus.SEARCHABLE_VALUES);
        allowed("state", state, RevisionState.VALUES);
        var scope = access.searchScope(actor);
        var filter = new CourseSearch(mode, actor.person().id(), actor.person().orgId(),
            identity.activeAncestorIds(actor.person().orgId()), scope, q, fieldId, programId, moduleId, level,
            requirement, learningStatus, state, overdue);

        // Bước 2: repository lọc quyền và đếm trên grain khóa học trước phân trang; không lọc trang trong memory.
        return repository.search(filter, pageable);
    }

    private void allowed(String field, String value, Set<String> options) {
        if (value != null && !options.contains(value)) {
            throw BusinessException.validation(field, "INVALID_VALUE", "Giá trị bộ lọc chưa hợp lệ.");
        }
    }
}

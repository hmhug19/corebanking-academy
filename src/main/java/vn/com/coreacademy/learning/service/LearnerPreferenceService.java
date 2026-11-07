package vn.com.coreacademy.learning.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.com.coreacademy.learning.dto.LearningPointsViews.LearnerPreferences;
import vn.com.coreacademy.learning.repository.LearnerPreferenceRepository;

/** Tùy chọn gamification của chính người gọi; không có cách nào đọc hay sửa tùy chọn của người khác. */
@Service
@Transactional
@RequiredArgsConstructor
public class LearnerPreferenceService {
    private final LearningAccessPolicy access;
    private final LearnerPreferenceRepository repository;

    @Transactional(readOnly = true)
    public LearnerPreferences mine() {
        var actor = access.actor();
        access.requireLearner(actor);
        return new LearnerPreferences(!repository.isHidden(actor.person().id()));
    }

    /** Đặt có hiện tên trên bảng xếp hạng của người khác hay không; trả giá trị đang áp dụng. */
    public LearnerPreferences update(boolean showOnLeaderboard) {
        var actor = access.actor();
        access.requireLearner(actor);
        repository.setHidden(actor.person().id(), !showOnLeaderboard);
        return new LearnerPreferences(showOnLeaderboard);
    }
}

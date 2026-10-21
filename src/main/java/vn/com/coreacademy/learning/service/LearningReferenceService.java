package vn.com.coreacademy.learning.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.com.coreacademy.learning.repository.LearningRepository;

/** Adapter công khai để Organization kiểm tra tham chiếu Learning mà không query chéo repository. */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class LearningReferenceService {
    private final LearningRepository repository;

    public boolean teamInUse(Long teamId) {
        return repository.teamInUse(teamId);
    }
}

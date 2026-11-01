package vn.com.coreacademy.learning.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.com.coreacademy.learning.entity.LearningActivityDay;
import vn.com.coreacademy.learning.repository.LearningActivityDayRepository;

/**
 * Ghi ngày học của người dùng cho chuỗi ngày học. Ghi nhận không bao giờ làm hỏng thao tác của học viên: lỗi chỉ
 * được log. Việc ghi chạy trong giao dịch riêng, vì một lỗi SQL trong giao dịch của thao tác học sẽ đánh dấu cả giao
 * dịch đó là hỏng; khi bên gọi đang trong giao dịch, việc ghi chờ đến sau khi giao dịch ấy commit để thao tác bị
 * rollback không để lại ngày học.
 */
@Slf4j
@Service
public class LearningActivityService {
    /** Ngày học tính theo giờ Việt Nam, không theo UTC: 00:30 sáng là ngày mới. */
    public static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private final LearningActivityDayRepository repository;
    private final TransactionTemplate isolated;

    public LearningActivityService(LearningActivityDayRepository repository,
                                   PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.isolated = new TransactionTemplate(transactionManager);
        this.isolated.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public static LocalDate localDate(Instant instant) {
        return LocalDate.ofInstant(instant, ZONE);
    }

    public void record(String userId, String source) {
        record(userId, source, Instant.now());
    }

    /** Ghi ngày chứa {@code at} cho người dùng nếu chưa có; {@code source} chỉ giữ khi đây là hoạt động đầu ngày. */
    public void record(String userId, String source, Instant at) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    write(userId, source, at);
                }
            });
            return;
        }
        write(userId, source, at);
    }

    private void write(String userId, String source, Instant at) {
        try {
            isolated.executeWithoutResult(status -> insertIfMissing(userId, source, localDate(at)));
        } catch (RuntimeException exception) {
            log.warn("Learning activity not recorded userId={} source={} type={}", userId, source,
                exception.getClass().getName());
            log.debug("Learning activity failure detail", exception);
        }
    }

    private void insertIfMissing(String userId, String source, LocalDate day) {
        repository.lockUser("learning-activity:" + userId);
        if (repository.existsByUserIdAndActivityDate(userId, day)) {
            return;
        }
        LearningActivityDay row = new LearningActivityDay();
        row.setUserId(userId);
        row.setActivityDate(day);
        row.setFirstSource(source);
        repository.save(row);
    }
}

package vn.com.coreacademy.learning.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import jakarta.annotation.PreDestroy;
import vn.com.coreacademy.learning.constants.LearningCodes.BadgeSource;
import vn.com.coreacademy.learning.constants.LearningCodes.BadgeStatus;
import vn.com.coreacademy.learning.repository.BadgeRepository;

/**
 * Trao bù huy hiệu ở nền, một luồng, để lưu huy hiệu trả lời ngay. Tìm người đủ điều kiện trong một giao dịch, rồi trao
 * từng lô nhỏ, mỗi lô một giao dịch cùng một lần cập nhật tiến độ, nên quản trị thấy tiến độ tăng dần. Trao âm thầm
 * (không thông báo) và an toàn khi chạy lại: mỗi (người, huy hiệu) một dòng. Ứng dụng khởi động lại giữa chừng thì lần
 * khởi động sau chạy lại các huy hiệu còn RUNNING.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BadgeBackfillJob {
    static final int CHUNK = 200;

    private final BadgeService badges;
    private final BadgeRepository repository;
    private final TransactionTemplate transaction;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "badge-backfill");
        thread.setDaemon(true);
        return thread;
    });

    /** Bắt đầu sau khi giao dịch hiện tại ghi xong, để luồng nền thấy huy hiệu vừa tạo. */
    public void startAfterCommit(long badgeId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            executor.execute(() -> run(badgeId));
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                executor.execute(() -> run(badgeId));
            }
        });
    }

    @EventListener(ApplicationReadyEvent.class)
    void resume() {
        transaction.execute(status -> repository.runningBackfills()).forEach(id -> executor.execute(() -> run(id)));
    }

    @PreDestroy
    void stop() {
        executor.shutdownNow();
    }

    private record Plan(List<String> users, String rule) {
    }

    private enum Chunk {
        AWARDED, RETIRED, SUPERSEDED
    }

    private static String ruleOf(BadgeRepository.Definition definition) {
        return definition.ruleType() + "|" + definition.paramN() + "|" + definition.paramProgramId();
    }

    void run(long badgeId) {
        try {
            Plan plan = transaction.execute(status -> {
                var definition = repository.definition(badgeId).orElse(null);
                if (definition == null || !BadgeStatus.ACTIVE.equals(definition.status())) {
                    repository.setBackfill(badgeId, "DONE", 0, 0);
                    return new Plan(List.of(), null);
                }
                Set<String> qualifiers = badges.qualifiers(badgeId);
                repository.setBackfill(badgeId, "RUNNING", qualifiers.size(), 0);
                return new Plan(new ArrayList<>(qualifiers.stream().sorted().toList()), ruleOf(definition));
            });
            List<String> users = plan.users();
            int total = users.size();
            int done = 0;
            boolean superseded = false;
            for (int from = 0; from < total; from += CHUNK) {
                List<String> chunk = users.subList(from, Math.min(total, from + CHUNK));
                int processed = done + chunk.size();
                Chunk result = transaction.execute(status -> {
                    var definition = repository.definition(badgeId).orElse(null);
                    if (definition == null || !BadgeStatus.ACTIVE.equals(definition.status())) {
                        return Chunk.RETIRED;
                    }
                    if (!plan.rule().equals(ruleOf(definition))) {
                        return Chunk.SUPERSEDED;
                    }
                    repository.awardAll(badgeId, chunk, BadgeSource.BACKFILL);
                    repository.setBackfill(badgeId, "RUNNING", total, processed);
                    return Chunk.AWARDED;
                });
                if (result == Chunk.SUPERSEDED) {
                    superseded = true;
                }
                if (result != Chunk.AWARDED) {
                    break;
                }
                done = processed;
            }
            if (superseded) {
                // Luật đã đổi: danh sách cũ bị bỏ, lần chạy mới (đã xếp hàng sau lần này) sẽ ghi trạng thái.
                return;
            }
            int finished = done;
            // Ngừng dùng giữa chừng: tiến độ ghi đúng số đã trao, không giả làm đủ.
            transaction.executeWithoutResult(status -> repository.setBackfill(badgeId, "DONE", finished, finished));
        } catch (RuntimeException exception) {
            log.error("Badge backfill {} failed", badgeId, exception);
            try {
                transaction.executeWithoutResult(status -> repository.failBackfill(badgeId));
            } catch (RuntimeException ignored) {
                log.error("Could not mark badge backfill {} as failed", badgeId);
            }
        }
    }
}

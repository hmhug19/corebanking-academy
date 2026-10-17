package vn.com.coreacademy.identity.service;

import jakarta.persistence.EntityManager;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class IdentityWriteLock {
    private final EntityManager entityManager;

    /**
     * Lấy advisory lock PostgreSQL dùng chung cho các thao tác ghi Identity giữa nhiều instance.
     *
     * <p>Bắt buộc có transaction; PostgreSQL tự nhả khóa khi commit/rollback. Mọi luồng ghi tham gia phải dùng
     * cùng khóa để bảo vệ kiểm tra alias, tổ chức/nhóm, phân quyền và quản trị viên cuối cùng.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void acquire() {
        entityManager.createNativeQuery("""
            select 1 from pg_advisory_xact_lock(7142051963)
            """).getSingleResult();
    }
}

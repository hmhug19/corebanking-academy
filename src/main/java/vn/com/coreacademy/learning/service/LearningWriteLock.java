package vn.com.coreacademy.learning.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vn.com.coreacademy.identity.service.IdentityWriteLock;

/**
 * Dùng chung khóa Identity để bảo vệ cả quan hệ xuyên module khi không có FK; xóa team không được chạy
 * xen giữa bước kiểm tra và ghi khóa học/giao học. Cần đo tải trước khi tách khóa chi tiết hơn.
 */
@Component
@RequiredArgsConstructor
public class LearningWriteLock {
    private final IdentityWriteLock identityWriteLock;

    @Transactional(propagation = Propagation.MANDATORY)
    public void acquire() {
        // Cùng transaction/connection với JPA; các luồng quản trị Identity cũng sử dụng khóa này.
        identityWriteLock.acquire();
    }
}

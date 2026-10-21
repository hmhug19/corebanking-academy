package vn.com.coreacademy.learning.controller;

import jakarta.persistence.OptimisticLockException;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import vn.com.coreacademy.common.dto.BaseResponse;
import vn.com.coreacademy.learning.exception.LearningResponseCode;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackages = "vn.com.coreacademy.learning.controller")
public class LearningExceptionHandler {
    @ExceptionHandler({OptimisticLockException.class, ObjectOptimisticLockingFailureException.class})
    public BaseResponse<Void> concurrentUpdate(Exception exception) {
        return BaseResponse.fail(LearningResponseCode.CONFLICT);
    }
}

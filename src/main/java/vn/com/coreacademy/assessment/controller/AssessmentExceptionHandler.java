package vn.com.coreacademy.assessment.controller;

import jakarta.persistence.OptimisticLockException;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import vn.com.coreacademy.assessment.exception.AssessmentResponseCode;
import vn.com.coreacademy.common.dto.BaseResponse;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackages = "vn.com.coreacademy.assessment.controller")
public class AssessmentExceptionHandler {
    @ExceptionHandler({OptimisticLockException.class, ObjectOptimisticLockingFailureException.class})
    public BaseResponse<Void> concurrentUpdate(Exception exception) {
        return BaseResponse.fail(AssessmentResponseCode.CONFLICT);
    }
}

package vn.com.coreacademy.learning.controller;

import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.com.coreacademy.learning.service.LearningMediaGatewayService;

@Hidden
@RestController
@Validated
@RequiredArgsConstructor
public class LearningMediaGatewayController {
    private final LearningMediaGatewayService gateway;

    @GetMapping("/api/v1/learning-assets/{assetId}/gateway-authorization")
    public ResponseEntity<Void> authorize(@PathVariable String assetId, @RequestParam(required = false) String blockId,
        @RequestParam(required = false) String enrollmentId,
        @RequestHeader(value = "X-Media-Gateway-Key", required = false) String key,
        @RequestHeader(value = "X-Original-Method", defaultValue = "GET") String method) {
        return ResponseEntity.ok().header("X-Media-S3-Url",
            gateway.authorize(assetId, blockId, enrollmentId, key, method)).build();
    }
}

package com.shubham.dev.bpm_agent.camunda.mock;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

@RestController
@RequestMapping("/api/mock-services")
public class MockServiceApiController {

    private final boolean random5xxEnabled;
    private final int randomFailureOneOutOf;

    public MockServiceApiController(
            @Value("${app.mock-services.random-5xx-enabled:true}") boolean random5xxEnabled,
            @Value("${app.mock-services.random-5xx-one-out-of:5}") int randomFailureOneOutOf) {
        this.random5xxEnabled = random5xxEnabled;
        this.randomFailureOneOutOf = Math.max(randomFailureOneOutOf, 1);
    }

    @PostMapping("/inventory/reserve")
    public ResponseEntity<Map<String, Object>> reserveInventory(@RequestBody MockServiceRequest request) {
        if (shouldForceStatus(request.forceHttpStatus())) {
            return forcedFailure("inventory-reservation", request.forceHttpStatus(), "Forced inventory mock response.");
        }

        if (shouldReturnRandomServerError()) {
            return serverError("inventory-reservation", "Inventory DB timeout triggered a mock HTTP 500 response.");
        }

        String stockStatus = "OUT".equalsIgnoreCase(request.simulateStock()) ? "OUT_OF_STOCK" : "IN_STOCK";
        return ResponseEntity.ok(Map.of(
                "service", "inventory-reservation",
                "orderId", request.orderId(),
                "stockStatus", stockStatus,
                "outcome", "SUCCESS"
        ));
    }

    @PostMapping("/payment/charge")
    public ResponseEntity<Map<String, Object>> chargePayment(@RequestBody MockServiceRequest request) {
        if (shouldForceStatus(request.forceHttpStatus())) {
            return forcedFailure("payment-charge", request.forceHttpStatus(), "Forced payment mock response.");
        }

        if ("BAD_REQUEST".equalsIgnoreCase(request.simulatePayment())) {
            return ResponseEntity.badRequest().body(Map.of(
                    "service", "payment-charge",
                    "orderId", request.orderId(),
                    "outcome", "BAD_REQUEST",
                    "message", "Payment payload is invalid for the downstream gateway."
            ));
        }

        if ("DECLINE".equalsIgnoreCase(request.simulatePayment())) {
            return ResponseEntity.ok(Map.of(
                    "service", "payment-charge",
                    "orderId", request.orderId(),
                    "outcome", "DECLINED",
                    "message", "Payment was declined by the downstream banking network."
            ));
        }

        if (shouldReturnRandomServerError()) {
            return serverError("payment-charge", "Payment gateway timeout triggered a mock HTTP 500 response.");
        }

        return ResponseEntity.ok(Map.of(
                "service", "payment-charge",
                "orderId", request.orderId(),
                "paymentStatus", "SUCCESS",
                "outcome", "SUCCESS"
        ));
    }

    private boolean shouldReturnRandomServerError() {
        return random5xxEnabled && ThreadLocalRandom.current().nextInt(randomFailureOneOutOf) == 0;
    }

    private boolean shouldForceStatus(Integer forceHttpStatus) {
        return forceHttpStatus != null && forceHttpStatus >= 400;
    }

    private ResponseEntity<Map<String, Object>> forcedFailure(String service, Integer status, String message) {
        HttpStatus httpStatus = HttpStatus.resolve(status);
        if (httpStatus == null) {
            httpStatus = HttpStatus.INTERNAL_SERVER_ERROR;
        }

        return ResponseEntity.status(httpStatus).body(Map.of(
                "service", service,
                "outcome", "FORCED_FAILURE",
                "message", message,
                "httpStatus", httpStatus.value()
        ));
    }

    private ResponseEntity<Map<String, Object>> serverError(String service, String message) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "service", service,
                "outcome", "TRANSIENT_FAILURE",
                "message", message,
                "httpStatus", 500
        ));
    }

    public record MockServiceRequest(
            String orderId,
            String simulateStock,
            String simulatePayment,
            Integer forceHttpStatus
    ) {
    }
}

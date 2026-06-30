package com.shubham.dev.bpm_agent.camunda.mock;

import org.springframework.http.HttpEntity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
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
    private final int random5xxOneOutOf;

    public MockServiceApiController(@Value("${app.mock-services.random-5xx-enabled:false}") boolean random5xxEnabled,
                                    @Value("${app.mock-services.random-5xx-one-out-of:5}") int random5xxOneOutOf) {
        this.random5xxEnabled = random5xxEnabled;
        this.random5xxOneOutOf = Math.max(random5xxOneOutOf, 1);
    }

    @PostMapping("/inventory/reserve")
    public ResponseEntity<Map<String, Object>> reserveInventory(@RequestBody MockServiceRequest request) {
        Integer forcedStatus = request.forceHttpStatus();
        if (forcedStatus != null) {
            return failure("Inventory DB timeout triggered a mock HTTP %d response.".formatted(forcedStatus),
                    "TRANSIENT_FAILURE", forcedStatus, "inventory-reservation");
        }
        if (shouldFailTransiently()) {
            return failure("Inventory DB timeout triggered a mock HTTP 500 response.",
                    "TRANSIENT_FAILURE", 500, "inventory-reservation");
        }
        if ("OUT_OF_STOCK".equalsIgnoreCase(request.simulateStock())) {
            return success(Map.of(
                    "stockStatus", "OUT_OF_STOCK",
                    "outcome", "BUSINESS_FAILURE",
                    "message", "Inventory simulation reported no stock for this order.",
                    "httpStatus", 200,
                    "service", "inventory-reservation"
            ));
        }
        return success(Map.of(
                "stockStatus", "IN_STOCK",
                "outcome", "SUCCESS",
                "message", "Inventory reserved successfully.",
                "httpStatus", 200,
                "service", "inventory-reservation"
        ));
    }

    @PostMapping("/payment/charge")
    public ResponseEntity<Map<String, Object>> chargePayment(@RequestBody MockServiceRequest request) {
        Integer forcedStatus = request.forceHttpStatus();
        if (forcedStatus != null) {
            return failure("Payment gateway returned a mock HTTP %d response.".formatted(forcedStatus),
                    forcedStatus >= 500 ? "TRANSIENT_FAILURE" : "REQUEST_FAILURE",
                    forcedStatus,
                    "payment-charge");
        }
        if ("BAD_REQUEST".equalsIgnoreCase(request.simulatePayment())) {
            return failure("Payment gateway rejected the request payload.",
                    "BAD_REQUEST", 400, "payment-charge");
        }
        if (shouldFailTransiently()) {
            return failure("Payment gateway returned a mock HTTP 500 response.",
                    "TRANSIENT_FAILURE", 500, "payment-charge");
        }
        if ("DECLINED".equalsIgnoreCase(request.simulatePayment()) || "DECLINE".equalsIgnoreCase(request.simulatePayment())) {
            return success(Map.of(
                    "paymentStatus", "DECLINED",
                    "outcome", "DECLINED",
                    "message", "Payment was declined by the mock gateway.",
                    "httpStatus", 200,
                    "service", "payment-charge"
            ));
        }
        return success(Map.of(
                "paymentStatus", "SUCCESS",
                "outcome", "SUCCESS",
                "message", "Payment captured successfully.",
                "httpStatus", 200,
                "service", "payment-charge"
        ));
    }

    private boolean shouldFailTransiently() {
        return random5xxEnabled && ThreadLocalRandom.current().nextInt(random5xxOneOutOf) == 0;
    }

    private ResponseEntity<Map<String, Object>> failure(String message, String outcome, int httpStatus, String service) {
        return ResponseEntity.status(HttpStatus.valueOf(httpStatus)).body(Map.of(
                "message", StringUtils.hasText(message) ? message : "Mock service failure.",
                "outcome", outcome,
                "httpStatus", httpStatus,
                "service", service
        ));
    }

    private ResponseEntity<Map<String, Object>> success(Map<String, Object> body) {
        return ResponseEntity.ok(body);
    }

    public record MockServiceRequest(String orderId,
                                     String simulateStock,
                                     String simulatePayment,
                                     Integer forceHttpStatus) {
    }
}

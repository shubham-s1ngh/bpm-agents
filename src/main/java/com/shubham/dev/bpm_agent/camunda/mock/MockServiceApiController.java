package com.shubham.dev.bpm_agent.camunda.mock;

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
    public ResponseEntity<?> reserveInventory(@RequestBody MockServiceClient.InventoryReservationRequest request) {
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
            return ResponseEntity.ok(new MockServiceClient.InventoryReservationResponse(
                    "OUT_OF_STOCK",
                    "BUSINESS_FAILURE",
                    "Inventory simulation reported no stock for this order.",
                    200,
                    "inventory-reservation"
            ));
        }
        return ResponseEntity.ok(new MockServiceClient.InventoryReservationResponse(
                "IN_STOCK",
                "SUCCESS",
                "Inventory reserved successfully.",
                200,
                "inventory-reservation"
        ));
    }

    @PostMapping("/payment/charge")
    public ResponseEntity<?> chargePayment(@RequestBody MockServiceClient.PaymentChargeRequest request) {
        Integer forcedStatus = request.forceHttpStatus();
        if (forcedStatus != null) {
            return failure("Payment gateway returned a mock HTTP %d response.".formatted(forcedStatus),
                    forcedStatus >= 500 ? "TRANSIENT_FAILURE" : "REQUEST_FAILURE",
                    forcedStatus,
                    "payment-charge");
        }
        if (shouldFailTransiently()) {
            return failure("Payment gateway returned a mock HTTP 500 response.",
                    "TRANSIENT_FAILURE", 500, "payment-charge");
        }
        if ("DECLINED".equalsIgnoreCase(request.simulatePayment())) {
            return ResponseEntity.ok(new MockServiceClient.PaymentChargeResponse(
                    "DECLINED",
                    "DECLINED",
                    "Payment was declined by the mock gateway.",
                    200,
                    "payment-charge"
            ));
        }
        return ResponseEntity.ok(new MockServiceClient.PaymentChargeResponse(
                "SUCCESS",
                "SUCCESS",
                "Payment captured successfully.",
                200,
                "payment-charge"
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
}

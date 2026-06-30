package com.shubham.dev.bpm_agent.camunda.mock;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class MockServiceClient {

    private final RestClient restClient;

    public MockServiceClient(RestClient.Builder restClientBuilder,
                             @Value("${app.mock-services.base-url:http://localhost:8082}") String baseUrl) {
        this.restClient = restClientBuilder
                .baseUrl(baseUrl)
                .build();
    }

    public InventoryReservationResponse reserveInventory(String orderId, String simulateStock, Integer forceHttpStatus) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("orderId", orderId);
        if (simulateStock != null) {
            request.put("simulateStock", simulateStock);
        }
        if (forceHttpStatus != null) {
            request.put("forceHttpStatus", forceHttpStatus);
        }

        return restClient.post()
                .uri("/api/mock-services/inventory/reserve")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(InventoryReservationResponse.class);
    }

    public PaymentChargeResponse chargePayment(String orderId, String simulatePayment, Integer forceHttpStatus) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("orderId", orderId);
        if (simulatePayment != null) {
            request.put("simulatePayment", simulatePayment);
        }
        if (forceHttpStatus != null) {
            request.put("forceHttpStatus", forceHttpStatus);
        }

        return restClient.post()
                .uri("/api/mock-services/payment/charge")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(PaymentChargeResponse.class);
    }

    public record InventoryReservationResponse(
            String service,
            String orderId,
            String stockStatus,
            String outcome
    ) {
    }

    public record PaymentChargeResponse(
            String service,
            String orderId,
            String paymentStatus,
            String outcome,
            String message
    ) {
    }
}

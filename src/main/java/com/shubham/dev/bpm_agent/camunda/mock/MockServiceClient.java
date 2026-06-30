package com.shubham.dev.bpm_agent.camunda.mock;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class MockServiceClient {

    private final RestClient restClient;

    public MockServiceClient(RestClient.Builder restClientBuilder,
                             @Value("${app.mock-services.base-url:http://localhost:8081}") String baseUrl) {
        this.restClient = restClientBuilder.baseUrl(baseUrl).build();
    }

    public InventoryReservationResponse reserveInventory(String orderId,
                                                         String simulateStock,
                                                         Integer forceHttpStatus) {
        return restClient.post()
                .uri("/api/mock-services/inventory/reserve")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new InventoryReservationRequest(orderId, simulateStock, forceHttpStatus))
                .retrieve()
                .body(InventoryReservationResponse.class);
    }

    public PaymentChargeResponse chargePayment(String orderId,
                                               String simulatePayment,
                                               Integer forceHttpStatus) {
        return restClient.post()
                .uri("/api/mock-services/payment/charge")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new PaymentChargeRequest(orderId, simulatePayment, forceHttpStatus))
                .retrieve()
                .body(PaymentChargeResponse.class);
    }

    public record InventoryReservationRequest(String orderId, String simulateStock, Integer forceHttpStatus) {
    }

    public record InventoryReservationResponse(String stockStatus, String outcome, String message, Integer httpStatus, String service) {
    }

    public record PaymentChargeRequest(String orderId, String simulatePayment, Integer forceHttpStatus) {
    }

    public record PaymentChargeResponse(String paymentStatus, String outcome, String message, Integer httpStatus, String service) {
    }
}

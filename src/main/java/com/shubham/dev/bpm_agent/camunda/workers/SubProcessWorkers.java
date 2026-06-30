package com.shubham.dev.bpm_agent.camunda.workers;

import com.shubham.dev.bpm_agent.camunda.mock.MockServiceClient;
import io.camunda.client.api.response.ActivatedJob;
import io.camunda.client.api.worker.JobClient;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientResponseException;

import java.util.Map;

/**
 * Programmatic job workers for advanced and complex sub-process orchestration tasks.
 * Follows the programmatic AgentWorkerFactory registration strategy.
 */
@Component
public class SubProcessWorkers {

    private final Logger log = LoggerFactory.getLogger(this.getClass());
    private final AgentWorkerFactory workerFactory;
    private final MockServiceClient mockServiceClient;
    private final boolean immediateIncidentOn5xxByDefault;

    public SubProcessWorkers(AgentWorkerFactory workerFactory,
                             MockServiceClient mockServiceClient,
                             @Value("${app.mock-services.immediate-incident-on-5xx:false}") boolean immediateIncidentOn5xxByDefault) {
        this.workerFactory = workerFactory;
        this.mockServiceClient = mockServiceClient;
        this.immediateIncidentOn5xxByDefault = immediateIncidentOn5xxByDefault;
    }

    @PostConstruct
    public void registerAll() {
        // 1. Inventory Sub-process Workers
        workerFactory.register("inventory-reservation", this::handleInventoryReservation);

        // 2. Payment Sub-process Workers
        workerFactory.register("payment-charge", this::handlePaymentCharge);

        // 3. Advanced Order Track Workers
        workerFactory.register("warehouse-prep-priority", this::handleWarehousePrepPriority);
        workerFactory.register("carrier-dispatch-priority", this::handleCarrierDispatchPriority);

        // 4. Regular Order Track Workers
        workerFactory.register("warehouse-pack-standard", this::handleWarehousePackStandard);
        workerFactory.register("carrier-dispatch-standard", this::handleCarrierDispatchStandard);
    }

    // ==========================================
    // INVENTORY SUB-PROCESS WORKERS
    // ==========================================
    private void handleInventoryReservation(JobClient client, ActivatedJob job) {
        Map<String, Object> variables = job.getVariablesAsMap();
        String orderId = (String) variables.get("orderId");
        log.info("📦 [Inventory System] Inspecting inventory buffers for orderId: {}", orderId);
        try {
            MockServiceClient.InventoryReservationResponse response = mockServiceClient.reserveInventory(
                    orderId,
                    valueAsString(variables.get("simulateStock")),
                    integerVariable(variables.get("inventoryForceHttpStatus"))
            );

            if ("OUT_OF_STOCK".equalsIgnoreCase(response.stockStatus())) {
                log.warn("⚠️ [Inventory System] Out-of-Stock simulated for orderId: {}", orderId);
            }

            client.newCompleteCommand(job.getKey())
                    .variables(Map.of("stockStatus", response.stockStatus()))
                    .send()
                    .join();
        } catch (RestClientResponseException exception) {
            handleConnectorFailure(
                    client,
                    job,
                    "inventory-reservation",
                    "subProcess_InventorySystem",
                    exception,
                    booleanVariable(variables.get("inventoryImmediateIncidentOn5xx"))
            );
        }
    }

    // ==========================================
    // PAYMENT SUB-PROCESS WORKERS
    // ==========================================
    private void handlePaymentCharge(JobClient client, ActivatedJob job) {
        Map<String, Object> variables = job.getVariablesAsMap();
        String orderId = (String) variables.get("orderId");
        log.info("💳 [Payment System] Executing card capture transaction for orderId: {}", orderId);
        try {
            MockServiceClient.PaymentChargeResponse response = mockServiceClient.chargePayment(
                    orderId,
                    valueAsString(variables.get("simulatePayment")),
                    integerVariable(variables.get("paymentForceHttpStatus"))
            );

            if ("DECLINED".equalsIgnoreCase(response.outcome())) {
                log.error("❌ [Payment System] Transaction declined. Escalating GATEWAY_DECLINE boundary error trap.");
                client.newThrowErrorCommand(job.getKey())
                        .errorCode("GATEWAY_DECLINE")
                        .errorMessage(response.message())
                        .send()
                        .join();
                return;
            }

            client.newCompleteCommand(job.getKey())
                    .variables(Map.of("paymentStatus", response.paymentStatus()))
                    .send()
                    .join();
        } catch (RestClientResponseException exception) {
            handleConnectorFailure(
                    client,
                    job,
                    "payment-charge",
                    "subProcess_PaymentGateway",
                    exception,
                    booleanVariable(variables.get("paymentImmediateIncidentOn5xx"))
            );
        }
    }

    // ==========================================
    // ADVANCED TRACK WORKERS
    // ==========================================
    private void handleWarehousePrepPriority(JobClient client, ActivatedJob job) {
        String orderId = (String) job.getVariablesAsMap().get("orderId");
        log.info("🔥 [Advanced Track] Preparing high-priority premium warehouse kit configurations for orderId: {}", orderId);

        client.newCompleteCommand(job.getKey()).send().join();
    }

    private void handleCarrierDispatchPriority(JobClient client, ActivatedJob job) {
        String orderId = (String) job.getVariablesAsMap().get("orderId");
        log.info("🚀 [Advanced Track] Dispatching premium expedited shipping container for orderId: {}", orderId);

        client.newCompleteCommand(job.getKey())
                .variables(Map.of("fulfillmentStatus", "COMPLETED_PRIORITY"))
                .send()
                .join();
    }

    // ==========================================
    // REGULAR TRACK WORKERS
    // ==========================================
    private void handleWarehousePackStandard(JobClient client, ActivatedJob job) {
        String orderId = (String) job.getVariablesAsMap().get("orderId");
        log.info("🛒 [Regular Track] Placing items into standard ground line packing conveyor belts for orderId: {}", orderId);

        client.newCompleteCommand(job.getKey()).send().join();
    }

    private void handleCarrierDispatchStandard(JobClient client, ActivatedJob job) {
        String orderId = (String) job.getVariablesAsMap().get("orderId");
        log.info("🚚 [Regular Track] Handing container over to standard ground distribution networks for orderId: {}", orderId);

        client.newCompleteCommand(job.getKey())
                .variables(Map.of("fulfillmentStatus", "COMPLETED_STANDARD"))
                .send()
                .join();
    }

    private void handleConnectorFailure(JobClient client,
                                        ActivatedJob job,
                                        String jobType,
                                        String processId,
                                        RestClientResponseException exception,
                                        Boolean immediateIncidentOverride) {
        int statusCode = exception.getStatusCode().value();
        String message = "%s in %s failed with HTTP %d %s. Response body: %s".formatted(
                jobType,
                processId,
                statusCode,
                exception.getStatusText(),
                exception.getResponseBodyAsString()
        );

        if (statusCode >= 500) {
            boolean immediateIncident = immediateIncidentOverride != null
                    ? immediateIncidentOverride
                    : immediateIncidentOn5xxByDefault;
            int retries = immediateIncident ? 0 : Math.max(job.getRetries() - 1, 0);
            log.warn("Retryable connector failure for {}. Remaining retries: {}", jobType, retries);
            client.newFailCommand(job.getKey())
                    .retries(retries)
                    .errorMessage(message)
                    .send()
                    .join();
            return;
        }

        log.warn("Non-retryable connector failure for {}. Setting retries to zero.", jobType);
        client.newFailCommand(job.getKey())
                .retries(0)
                .errorMessage(message)
                .send()
                .join();
    }

    private String valueAsString(Object value) {
        return value == null ? null : value.toString();
    }

    private Integer integerVariable(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            return Integer.parseInt(text);
        }
        return null;
    }

    private Boolean booleanVariable(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String text && !text.isBlank()) {
            return Boolean.parseBoolean(text);
        }
        return null;
    }
}

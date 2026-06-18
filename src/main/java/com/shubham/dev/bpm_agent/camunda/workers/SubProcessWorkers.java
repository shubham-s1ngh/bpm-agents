package com.shubham.dev.bpm_agent.camunda.workers;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.response.ActivatedJob;
import io.camunda.client.api.worker.JobClient;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Map;

/**
 * Programmatic job workers for advanced and complex sub-process orchestration tasks.
 * Follows the programmatic AgentWorkerFactory registration strategy.
 */
@Component
public class SubProcessWorkers {

    private final Logger log = LoggerFactory.getLogger(this.getClass());
    private final AgentWorkerFactory workerFactory;
    private final CamundaClient camundaClient;

    public SubProcessWorkers(AgentWorkerFactory workerFactory, CamundaClient camundaClient) {
        this.workerFactory = workerFactory;
        this.camundaClient = camundaClient;
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

        // Simulate out-of-stock trigger condition if variable sets request it
        String stockStatus = "IN_STOCK";
        if ("OUT".equalsIgnoreCase((String) variables.get("simulateStock"))) {
            stockStatus = "OUT_OF_STOCK";
            log.warn("⚠️ [Inventory System] Out-of-Stock simulated for orderId: {}", orderId);
        }

        client.newCompleteCommand(job.getKey())
                .variables(Map.of("stockStatus", stockStatus))
                .send()
                .join();
    }

    // ==========================================
    // PAYMENT SUB-PROCESS WORKERS
    // ==========================================
    private void handlePaymentCharge(JobClient client, ActivatedJob job) {
        Map<String, Object> variables = job.getVariablesAsMap();
        String orderId = (String) variables.get("orderId");
        log.info("💳 [Payment System] Executing card capture transaction for orderId: {}", orderId);

        // Simulate a failure path if requesting credit route triggers a decline error
        if ("DECLINE".equalsIgnoreCase((String) variables.get("simulatePayment"))) {
            log.error("❌ [Payment System] Transaction declined. Escalating GATEWAY_DECLINE boundary error trap.");
            client.newThrowErrorCommand(job.getKey())
                    .errorCode("GATEWAY_DECLINE")
                    .errorMessage("Credit card transaction was explicitly declined by downstream banking network.")
                    .send()
                    .join();
            return;
        }

        client.newCompleteCommand(job.getKey())
                .variables(Map.of("paymentStatus", "SUCCESS"))
                .send()
                .join();
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
}

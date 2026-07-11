package com.shubham.dev.bpm_agent.strategy;

import com.shubham.dev.bpm_agent.chat.model.incident.IncidentResolutionContext;
import com.shubham.dev.bpm_agent.chat.model.incident.IncidentResolutionMode;
import com.shubham.dev.bpm_agent.chat.model.incident.IncidentResolutionRule;
import com.shubham.dev.bpm_agent.strategy.persistence.IncidentResolutionRuleCatalogService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class OrderWorkflowStrategy implements WorkflowContextStrategy {

    private final IncidentResolutionRuleCatalogService ruleCatalogService;

    @Autowired
    public OrderWorkflowStrategy(IncidentResolutionRuleCatalogService ruleCatalogService) {
        this.ruleCatalogService = ruleCatalogService;
    }

    @Override
    public String getProcessDefinitionId() {
        return "handleOrderId";
    }

    @Override
    public boolean isApplicable(String userPrompt) {
        String promptLower = userPrompt.toLowerCase();
        return promptLower.contains("order")
                || promptLower.contains("category")
                || promptLower.contains("handleorder")
                || !extractBusinessIdentifiers(userPrompt).isEmpty();
    }

    @Override
    public String generateBpmnContextInstructions() {
        return """
        ENTERPRISE COGNITIVE RUNTIMES FOR WORKFLOW: [handleOrderId]

        MAIN PROCESS FLOW:
        1. `sanitize order payload` script task sets `isValid` from `orderId`.
        2. `inventory service` calls process `subProcess_InventorySystem` with child-variable propagation enabled.
        3. `payment service` calls process `subProcess_PaymentGateway` with child-variable propagation enabled.
        4. `evaluate order category` script task sets variable `path` using this exact logic:
           - `category = "1"` -> `path = "premier"`
           - `category = "2"` -> `path = "advanced"`
           - otherwise -> `path = "regular"`
        5. Gateway `decides category` has only one explicit conditional branch:
           - if `path = "advanced"` -> call `advanceCategory_processId`
           - default branch -> call `regularCategory_ProcessId`
        6. After branch completion, the process merges and calls `subProcess_NotificationSystem`.

        IMPORTANT BPMN ROUTING FACT:
        - In the current BPMN, `category = "1"` produces `path = "premier"` but there is no dedicated premier branch at the gateway.
        - Because the gateway default goes to `regular track`, category `1` currently falls through to `regularCategory_ProcessId`.
        - The strategy must describe this as the current deployed behavior, not the intended business behavior.

        PAYMENT FAILURE PATH:
        1. `payment service` has boundary event `Payment Failure Trap`.
        2. It catches BPMN error code `PAYMENT_REJECTED`.
        3. The boundary path executes service task `Rollback Stock Inventory` with job type `inventory-rollback`.
        4. The process then ends at `Fulfillment Aborted`.

        CHILD PROCESS JOB WORKER MATRIX:
        1. `subProcess_InventorySystem`
           - Job type `inventory-reservation`
           - Sets `stockStatus`
           - Contains gateway `Stock Available?`
           - Has boundary event `Allocation SLA Timeout`
        2. `subProcess_PaymentGateway`
           - Job type `payment-charge`
           - Can set `paymentStatus`
           - Has boundary event `Network or Decline Trap`
           - Can execute retry step `Re-try Payment Capture`
        3. `advanceCategory_processId`
           - Job type `warehouse-prep-priority`
           - Job type `carrier-dispatch-priority`
        4. `regularCategory_ProcessId`
           - Job type `warehouse-pack-standard`
           - Boundary event `2 Day SLA Delay`
           - Job type `logistics-delay-notice`
           - Job type `carrier-dispatch-standard`
        5. `subProcess_NotificationSystem`
           - Job type `send-confirmation-email`
        """;
    }

    @Override
    public String generateBusinessIdentifierInstructions() {
        return """
            BUSINESS IDENTIFIER NORMALIZATION FOR [handleOrderId]:
            - Normalize `order id`, `order-id`, `OrderID`, and `orderId` to the canonical Camunda variable name `orderId`.
            - If the prompt contains text like `orderId ORD-55421`, call `searchProcessInstances` with `variableName=orderId` and `variableValue=ORD-55421`.
            - If the prompt mentions category values such as `advanced`, `premier`, `high priority`, or `regular`, resolve them using the exact deployed BPMN behavior described in this strategy.
            - Do not assume that shared identifiers from other workflows belong to `handleOrderId` unless the prompt context matches this workflow.
            """;
    }

    @Override
    public String primaryBusinessIdentifierVariable() {
        return "orderId";
    }

    @Override
    public List<String> extractBusinessIdentifiers(String userPrompt) {
        Matcher matcher = Pattern.compile("\\bORD-\\d+\\b", Pattern.CASE_INSENSITIVE).matcher(userPrompt);
        Set<String> orderIds = new LinkedHashSet<>();
        while (matcher.find()) {
            orderIds.add(matcher.group().toUpperCase(Locale.ROOT));
        }
        return List.copyOf(orderIds);
    }

    @Override
    public String generateReportStructuringInstructions() {
        return """
            WORKFLOW-SPECIFIC REPORTING RULES FOR [handleOrderId]:
            - Under `## Variables`, prefer showing `orderId`, `category`, `stockStatus`, `paymentStatus`, `simulatePayment`, and `path` when those values are present in Camunda evidence.
            - Under `## Flow Elements`, list the main-process elements in execution order using the raw Camunda element IDs, names, types, and states.
            - Under `## Child Processes`, include each child process instance key, process definition ID, state, and any child flow elements returned by Camunda.
            - If `category = "2"`, explain inline that the order is on the advanced branch.
            - If `category = "1"`, explain inline that the current BPMN sets `path = "premier"` but falls through the default gateway branch because no dedicated premier branch exists.
            - If active incidents exist and the payload shows payment failure evidence, explain inline that the BPMN rollback path is `Payment Failure Trap` -> `Rollback Stock Inventory` -> `Fulfillment Aborted`.
            - When incidents exist, quote the exact `errorType` and `errorMessage` values from Camunda evidence.
            - Do not add extra headings beyond the stable report contract from the report service.
            """;
    }

    @Override
    public List<IncidentResolutionRule> incidentResolutionRules() {
        List<IncidentResolutionRule> persistedRules = ruleCatalogService.findRulesForWorkflows(managedProcessDefinitionIds());
        if (!persistedRules.isEmpty()) {
            return persistedRules;
        }
        return defaultIncidentResolutionRules();
    }

    private Set<String> managedProcessDefinitionIds() {
        return Set.of(
                "handleOrderId",
                "subProcess_InventorySystem",
                "subProcess_PaymentGateway",
                "advanceCategory_processId",
                "regularCategory_ProcessId",
                "subProcess_NotificationSystem"
        );
    }

    private List<IncidentResolutionRule> defaultIncidentResolutionRules() {
        return List.of(
                new IncidentResolutionRule(
                        "Block retry when the incident indicates a called-process deployment or BPMN configuration mismatch.",
                        managedProcessDefinitionIds(),
                        Set.of("called_element_error"),
                        Set.of(),
                        List.of("called element", "deployment", "not found"),
                        IncidentResolutionMode.BLOCKED,
                        "Order workflow blocks retry for called-element deployment incidents until BPMN deployment alignment is fixed.",
                        "Retry is blocked for this order workflow because the incident points to a called-process deployment or configuration mismatch. Align the parent BPMN called-process ID with the deployed child BPMN before retrying."
                ),
                new IncidentResolutionRule(
                        "Allow retry for transient HTTP 500 failures from the inventory reservation connector or worker path.",
                        Set.of("handleOrderId", "subProcess_InventorySystem"),
                        Set.of("job_no_retries"),
                        Set.of(500),
                        List.of("inventory-reservation", "subprocess_inventorysystem", "inventory system"),
                        IncidentResolutionMode.BY_PROCESS_INSTANCE,
                        "Order workflow allows retry for transient inventory infrastructure failures that surfaced as HTTP 500 incidents.",
                        ""
                ),
                new IncidentResolutionRule(
                        "Allow retry for transient HTTP 500 failures from the payment charge connector or worker path.",
                        Set.of("handleOrderId", "subProcess_PaymentGateway"),
                        Set.of("job_no_retries"),
                        Set.of(500),
                        List.of("payment-charge", "subprocess_paymentgateway", "payment system"),
                        IncidentResolutionMode.BY_PROCESS_INSTANCE,
                        "Order workflow allows retry for transient payment infrastructure failures that surfaced as HTTP 500 incidents.",
                        ""
                ),
                new IncidentResolutionRule(
                        "Block retry for HTTP 400 bad request errors from payment capture because the request payload must be corrected first.",
                        Set.of("handleOrderId", "subProcess_PaymentGateway"),
                        Set.of("job_no_retries"),
                        Set.of(400),
                        List.of("payment-charge", "bad request"),
                        IncidentResolutionMode.BLOCKED,
                        "Order workflow blocks retry for payment bad-request incidents because the downstream service rejected the request payload.",
                        "Retry is blocked for this order workflow because payment capture failed with HTTP 400 Bad Request. Correct the payment request data or worker input before retrying."
                )
        );
    }

    @Override
    public String buildResolutionGuidance(IncidentResolutionContext context) {
        return "For order workflows, prefer process-instance incident resolution for transient server-side failures, but block retries for deployment mismatches and payment bad-request incidents.";
    }

    @Override
    public Map<String, String> translateVariables(String userPrompt) {
        Map<String, String> resolvedVariables = new HashMap<>();
        String promptLower = userPrompt.toLowerCase();
        if (promptLower.contains("advanced")) resolvedVariables.put("category", "2");
        else if (promptLower.contains("premier") || promptLower.contains("high priority")) resolvedVariables.put("category", "1");
        else if (promptLower.contains("regular")) resolvedVariables.put("category", "3");
        return resolvedVariables;
    }
}

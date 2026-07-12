package com.shubham.dev.bpm_agent.strategy.retrieval;

import com.shubham.dev.bpm_agent.strategy.WorkflowContextStrategy;
import com.shubham.dev.bpm_agent.strategy.persistence.IncidentResolutionRuleRepository;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowKnowledgeVectorStoreServiceTest {

    @Test
    void returnsFallbackContextUntilStartupInitializationHasRun() {
        VectorStore vectorStore = mock(VectorStore.class);
        IncidentResolutionRuleRepository repository = mock(IncidentResolutionRuleRepository.class);
        WorkflowContextStrategy strategy = mock(WorkflowContextStrategy.class);

        when(strategy.getProcessDefinitionId()).thenReturn("handleOrderId");
        when(strategy.primaryBusinessIdentifierVariable()).thenReturn("orderId");
        when(strategy.generateBusinessIdentifierInstructions()).thenReturn("Normalize order references to orderId.");
        when(strategy.generateBpmnContextInstructions()).thenReturn("Inventory, payment, regular track, notification.");
        when(strategy.generateReportStructuringInstructions()).thenReturn("Mention child processes inline.");

        WorkflowKnowledgeVectorStoreService service = new WorkflowKnowledgeVectorStoreService(
                vectorStore,
                List.of(strategy),
                repository,
                new BpmnKnowledgeExtractor(),
                true,
                4,
                "build/test-vector-store.json"
        );

        String context = service.fetchRelevantContext("what happened for order ORD-55447", Optional.of(strategy));

        assertTrue(context.contains("Workflow strategy: handleOrderId"));
        verify(vectorStore, never()).similaritySearch(any(SearchRequest.class));
    }

    @Test
    void returnsStrategyScopedRetrievedKnowledgeAfterInitialization() {
        VectorStore vectorStore = mock(VectorStore.class);
        IncidentResolutionRuleRepository repository = mock(IncidentResolutionRuleRepository.class);
        WorkflowContextStrategy strategy = mock(WorkflowContextStrategy.class);

        when(strategy.getProcessDefinitionId()).thenReturn("handleOrderId");
        when(strategy.primaryBusinessIdentifierVariable()).thenReturn("orderId");
        when(strategy.generateBusinessIdentifierInstructions()).thenReturn("Normalize order references to orderId.");
        when(strategy.generateBpmnContextInstructions()).thenReturn("Inventory, payment, regular track, notification.");
        when(strategy.generateReportStructuringInstructions()).thenReturn("Mention child processes inline.");
        when(repository.findAllByOrderByWorkflowProcessDefinitionIdAscPriorityAscIdAsc()).thenReturn(List.of());
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(
                Document.builder()
                        .id("strategy-context-handleOrderId")
                        .text("Workflow process definition: handleOrderId\nInventory, payment, regular track, notification.")
                        .metadata(Map.of(
                                "workflowProcessDefinitionId", "handleOrderId",
                                "knowledgeType", "workflow-context"
                        ))
                        .build(),
                Document.builder()
                        .id("strategy-context-loanProcessingFlow")
                        .text("Workflow process definition: loanProcessingFlow")
                        .metadata(Map.of(
                                "workflowProcessDefinitionId", "loanProcessingFlow",
                                "knowledgeType", "workflow-context"
                        ))
                        .build()
        ));

        WorkflowKnowledgeVectorStoreService service = new WorkflowKnowledgeVectorStoreService(
                vectorStore,
                List.of(strategy),
                repository,
                new BpmnKnowledgeExtractor(),
                true,
                4,
                "build/test-vector-store.json"
        );

        service.initializeIndexOnStartup();
        String context = service.fetchRelevantContext("what happened for order ORD-55447", Optional.of(strategy));

        assertTrue(context.contains("handleOrderId"));
        assertFalse(context.contains("loanProcessingFlow"));
        verify(vectorStore).add(any());
        verify(vectorStore).similaritySearch(any(SearchRequest.class));
    }

    @Test
    void indexesStructuredBpmnDocumentsForReachableChildProcesses() {
        VectorStore vectorStore = mock(VectorStore.class);
        IncidentResolutionRuleRepository repository = mock(IncidentResolutionRuleRepository.class);
        WorkflowContextStrategy strategy = mock(WorkflowContextStrategy.class);

        when(strategy.getProcessDefinitionId()).thenReturn("handleOrderId");
        when(strategy.primaryBusinessIdentifierVariable()).thenReturn("orderId");
        when(strategy.generateBusinessIdentifierInstructions()).thenReturn("Normalize order references to orderId.");
        when(strategy.generateBpmnContextInstructions()).thenReturn("Order workflow with inventory, payment, fulfillment, and notification.");
        when(strategy.generateReportStructuringInstructions()).thenReturn("Mention child processes inline.");
        when(repository.findAllByOrderByWorkflowProcessDefinitionIdAscPriorityAscIdAsc()).thenReturn(List.of());

        final List<Document>[] capturedDocuments = new List[1];
        doAnswer(invocation -> {
            capturedDocuments[0] = List.copyOf(invocation.getArgument(0));
            return null;
        }).when(vectorStore).add(any());

        WorkflowKnowledgeVectorStoreService service = new WorkflowKnowledgeVectorStoreService(
                vectorStore,
                List.of(strategy),
                repository,
                new BpmnKnowledgeExtractor(),
                true,
                4,
                "build/test-vector-store.json"
        );

        service.initializeIndexOnStartup();

        String joinedTexts = capturedDocuments[0].stream()
                .filter(document -> "bpmn".equals(document.getMetadata().get("knowledgeType")))
                .map(Document::getText)
                .collect(Collectors.joining("\n\n"));

        assertTrue(joinedTexts.contains("BPMN process definition: handleOrderId"));
        assertTrue(joinedTexts.contains("called process subProcess_InventorySystem"));
        assertTrue(joinedTexts.contains("called process regularCategory_ProcessId1"));
        assertTrue(joinedTexts.contains("Service task Task_ReserveStock / Allocate Stock in Warehouse / worker type inventory-reservation"));
        assertTrue(joinedTexts.contains("Boundary event Event_PaymentError_Trap / Payment Failure Trap"));
    }

    @Test
    void refreshIndexReplacesExistingCorpusBeforeReaddingDocuments() {
        VectorStore vectorStore = mock(VectorStore.class);
        IncidentResolutionRuleRepository repository = mock(IncidentResolutionRuleRepository.class);
        WorkflowContextStrategy strategy = mock(WorkflowContextStrategy.class);

        when(strategy.getProcessDefinitionId()).thenReturn("syntheticFlow");
        when(strategy.generateBusinessIdentifierInstructions()).thenReturn("Use syntheticId.");
        when(strategy.generateBpmnContextInstructions()).thenReturn("Synthetic BPMN context.");
        when(strategy.generateReportStructuringInstructions()).thenReturn("Report synthetic state.");
        when(repository.findAllByOrderByWorkflowProcessDefinitionIdAscPriorityAscIdAsc()).thenReturn(List.of());

        WorkflowKnowledgeVectorStoreService service = new WorkflowKnowledgeVectorStoreService(
                vectorStore,
                List.of(strategy),
                repository,
                new BpmnKnowledgeExtractor(),
                true,
                4,
                "build/test-vector-store.json"
        );

        service.refreshIndex();
        service.refreshIndex();

        var callOrder = inOrder(vectorStore);
        callOrder.verify(vectorStore).add(any());
        callOrder.verify(vectorStore).delete(argThat((List<String> ids) ->
                ids.size() == 1 && ids.contains("strategy-context-syntheticFlow")));
        callOrder.verify(vectorStore).add(any());
    }
}

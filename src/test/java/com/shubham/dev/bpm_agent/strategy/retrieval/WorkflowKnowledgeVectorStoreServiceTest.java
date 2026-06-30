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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowKnowledgeVectorStoreServiceTest {

    @Test
    void returnsStrategyScopedRetrievedKnowledge() {
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
                        .text("Workflow process definition: handleOrderId")
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
                true,
                4,
                "build/test-vector-store.json"
        );

        service.refreshIndex();
        String context = service.fetchRelevantContext("what happened for order ORD-55447", Optional.of(strategy));

        assertTrue(context.contains("handleOrderId"));
        assertFalse(context.contains("loanProcessingFlow"));
        verify(vectorStore).add(any());
        verify(vectorStore).similaritySearch(any(SearchRequest.class));
    }
}

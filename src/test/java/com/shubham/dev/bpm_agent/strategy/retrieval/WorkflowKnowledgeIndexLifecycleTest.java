package com.shubham.dev.bpm_agent.strategy.retrieval;

import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class WorkflowKnowledgeIndexLifecycleTest {

    @Test
    void initializesKnowledgeIndexOnApplicationReady() {
        WorkflowKnowledgeVectorStoreService service = mock(WorkflowKnowledgeVectorStoreService.class);
        WorkflowKnowledgeIndexLifecycle lifecycle = new WorkflowKnowledgeIndexLifecycle(service);

        lifecycle.initializeIndexOnStartup();

        verify(service).initializeIndexOnStartup();
    }
}

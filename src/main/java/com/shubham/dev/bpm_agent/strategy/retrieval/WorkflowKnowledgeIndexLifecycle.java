package com.shubham.dev.bpm_agent.strategy.retrieval;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class WorkflowKnowledgeIndexLifecycle {

    private final WorkflowKnowledgeVectorStoreService workflowKnowledgeVectorStoreService;

    public WorkflowKnowledgeIndexLifecycle(WorkflowKnowledgeVectorStoreService workflowKnowledgeVectorStoreService) {
        this.workflowKnowledgeVectorStoreService = workflowKnowledgeVectorStoreService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void initializeIndexOnStartup() {
        workflowKnowledgeVectorStoreService.initializeIndexOnStartup();
    }
}

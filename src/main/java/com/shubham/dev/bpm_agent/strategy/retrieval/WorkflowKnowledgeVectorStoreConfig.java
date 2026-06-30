package com.shubham.dev.bpm_agent.strategy.retrieval;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class WorkflowKnowledgeVectorStoreConfig {

    @Bean(name = "workflowKnowledgeVectorStore")
    public VectorStore workflowKnowledgeVectorStore(EmbeddingModel embeddingModel) {
        return SimpleVectorStore.builder(embeddingModel).build();
    }
}

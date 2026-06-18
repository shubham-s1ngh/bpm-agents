package com.shubham.dev.bpm_agent.camunda;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.*;

@Component
public class CamundaOrchestrationClient {

    private static final Logger log = LoggerFactory.getLogger(CamundaOrchestrationClient.class);
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public CamundaOrchestrationClient(RestClient.Builder restClientBuilder, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.restClient = restClientBuilder
                .baseUrl("http://localhost:8080/v2") // Replace with your target cluster gateway base address
                .build();
    }

    public List<Map<String, Object>> searchProcessInstancesByVariable(String name, String value, int limit) throws JsonProcessingException {
        Map<String, Object> requestPayload = Map.of(
                "filter", Map.of(
                        "variables", List.of(Map.of("name", name, "value", "\"" + value + "\""))
                ),
                "page", Map.of("limit", limit)
        );
        return executePost("/process-instances/search", requestPayload);
    }

    public List<Map<String, Object>> searchProcessInstancesByInstanceKey(Long processInstanceKey, int limit) throws JsonProcessingException {
        Map<String, Object> requestPayload = Map.of(
                "filter", Map.of("processInstanceKey", String.valueOf(processInstanceKey)),
                "page", Map.of("limit", limit)
        );
        return executePost("/process-instances/search", requestPayload);
    }

    public List<Map<String, Object>> searchChildProcessInstances(Long parentProcessInstanceKey, int limit) throws JsonProcessingException {
        Map<String, Object> requestPayload = Map.of(
                "filter", Map.of("parentProcessInstanceKey", String.valueOf(parentProcessInstanceKey)),
                "page", Map.of("limit", limit)
        );
        return executePost("/process-instances/search", requestPayload);
    }

    public List<Map<String, Object>> searchVariablesByInstanceKey(Long processInstanceKey, int limit) throws JsonProcessingException {
        Map<String, Object> requestPayload = Map.of(
                // FIXED FOR V2 SPECIFICATION: processInstanceKey must pass as a string token type
                "filter", Map.of("processInstanceKey", String.valueOf(processInstanceKey)),
                "page", Map.of("limit", limit)
        );
        return executePost("/variables/search", requestPayload);
    }

    public List<Map<String, Object>> getIncidents(Long processInstanceKey) throws JsonProcessingException {
        Map<String, Object> requestPayload = Map.of(
                // FIXED FOR V2 SPECIFICATION: processInstanceKey must pass as a string token type
                "filter", Map.of("processInstanceKey", String.valueOf(processInstanceKey)),
                "page", Map.of("limit", 50)
        );
        return executePost("/incidents/search", requestPayload);
    }

    public List<Map<String, Object>> getFlowNodes(Long processInstanceKey) throws JsonProcessingException {
        Map<String, Object> requestPayload = Map.of(
                // FIXED FOR V2 SPECIFICATION: processInstanceKey must pass as a string token type
                "filter", Map.of("processInstanceKey", String.valueOf(processInstanceKey)),
                "page", Map.of("limit", 100)
        );
        return executePost("/element-instances/search", requestPayload);
    }

    public Map<String, Object> getIncidentByKey(String incidentKey) {
        try {
            log.info("\n>>> [CAMUNDA OUTBOUND REQUEST] /incidents/{}", incidentKey);

            Map<String, Object> response = restClient.get()
                    .uri("/incidents/{incidentKey}", incidentKey)
                    .retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() {});

            log.info("\n<<< [CAMUNDA INBOUND RESPONSE] SUCCESS /incidents/{}", incidentKey);
            return response == null ? Map.of() : response;
        } catch (Exception e) {
            log.error("[CAMUNDA REST API EXCEPTION] Request to '/incidents/{}' failed: {}", incidentKey, e.getMessage());
            throw e;
        }
    }

    public void resolveIncidentsByProcessInstance(Long processInstanceKey) throws JsonProcessingException {
        executeMutationPost("/process-instances/" + processInstanceKey + "/incident-resolution", Map.of());
    }

    public void executeMutationPost(String endpoint, Object payload) throws JsonProcessingException {
        try {
            log.info("\n>>> [CAMUNDA OUTBOUND MUTATION] {}\n{}", endpoint, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(payload));

            restClient.post()
                    .uri(endpoint)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .toBodilessEntity();

            log.info("\n<<< [CAMUNDA INBOUND MUTATION] SUCCESS {}", endpoint);
        } catch (Exception e) {
            log.error("[CAMUNDA REST API EXCEPTION] Mutation request to '{}' failed: {}", endpoint, e.getMessage());
            throw e;
        }
    }

    public List<Map<String, Object>> executePost(String endpoint, Object payload) throws JsonProcessingException {
        try {
            log.info("\n>>> [CAMUNDA OUTBOUND REQUEST] {}\n{}", endpoint, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(payload));

            Map<String, Object> response = restClient.post()
                    .uri(endpoint)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() {});

            log.info("\n<<< [CAMUNDA INBOUND RESPONSE] SUCCESS {}", endpoint);
            return response != null && response.get("items") != null ? (List<Map<String, Object>>) response.get("items") : List.of();
        } catch (Exception e) {
            log.error("[CAMUNDA REST API EXCEPTION] Request to '{}' failed: {}", endpoint, e.getMessage());
            throw e;
        }
    }
}

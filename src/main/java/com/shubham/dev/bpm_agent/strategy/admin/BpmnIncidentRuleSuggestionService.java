package com.shubham.dev.bpm_agent.strategy.admin;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class BpmnIncidentRuleSuggestionService {

    private static final Logger log = LoggerFactory.getLogger(BpmnIncidentRuleSuggestionService.class);
    private static final Set<String> CANDIDATE_TYPES = Set.of("serviceTask", "businessRuleTask", "sendTask", "callActivity");

    private final ChatClient.Builder chatClientBuilder;
    private final ObjectMapper objectMapper;

    public BpmnIncidentRuleSuggestionService(ChatClient.Builder chatClientBuilder, ObjectMapper objectMapper) {
        this.chatClientBuilder = chatClientBuilder;
        this.objectMapper = objectMapper;
    }

    public BpmnRuleSuggestionResponse suggestRules(List<MultipartFile> files, String consultantNotes) {
        if (files == null || files.isEmpty()) {
            throw new IllegalArgumentException("At least one BPMN file must be uploaded.");
        }

        List<BpmnProcessSummary> processSummaries = new ArrayList<>();
        List<BpmnIncidentCandidate> allCandidates = new ArrayList<>();

        for (MultipartFile file : files) {
            if (file == null || file.isEmpty()) {
                continue;
            }
            processSummaries.addAll(extractProcessSummaries(file));
        }

        for (BpmnProcessSummary summary : processSummaries) {
            allCandidates.addAll(summary.candidates());
        }

        if (allCandidates.isEmpty()) {
            throw new IllegalArgumentException("No service tasks or call activities were found in the uploaded BPMN files.");
        }

        List<BpmnIncidentRuleSuggestion> fallbackSuggestions = buildFallbackSuggestions(allCandidates);
        List<BpmnIncidentRuleSuggestion> draftedSuggestions = draftSuggestionsWithLlm(processSummaries, consultantNotes, fallbackSuggestions);

        return new BpmnRuleSuggestionResponse(processSummaries, draftedSuggestions);
    }

    List<BpmnProcessSummary> extractProcessSummaries(MultipartFile file) {
        try {
            String xml = new String(file.getBytes(), StandardCharsets.UTF_8);
            return extractProcessSummaries(file.getOriginalFilename(), xml);
        } catch (IOException ex) {
            throw new IllegalArgumentException("Failed to read BPMN file " + file.getOriginalFilename() + ".", ex);
        }
    }

    List<BpmnProcessSummary> extractProcessSummaries(String fileName, String xml) {
        try {
            Document document = parseXml(xml);
            NodeList processNodes = document.getElementsByTagNameNS("*", "process");
            List<BpmnProcessSummary> summaries = new ArrayList<>();
            for (int index = 0; index < processNodes.getLength(); index++) {
                Node node = processNodes.item(index);
                if (!(node instanceof Element processElement)) {
                    continue;
                }

                String processId = normalizeText(processElement.getAttribute("id"));
                if (!StringUtils.hasText(processId)) {
                    continue;
                }

                String processName = firstNonBlank(processElement.getAttribute("name"), processId);
                List<BpmnIncidentCandidate> candidates = new ArrayList<>();
                collectCandidates(processElement, fileName, processId, processName, candidates);
                summaries.add(new BpmnProcessSummary(fileName, processId, processName, candidates));
            }
            return summaries;
        } catch (Exception ex) {
            throw new IllegalArgumentException("Failed to parse BPMN XML from " + fileName + ": " + ex.getMessage(), ex);
        }
    }

    private void collectCandidates(Element parent,
                                   String fileName,
                                   String processId,
                                   String processName,
                                   List<BpmnIncidentCandidate> candidates) {
        NodeList children = parent.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            Node child = children.item(index);
            if (!(child instanceof Element element)) {
                continue;
            }

            String localName = element.getLocalName();
            if (localName == null) {
                localName = element.getNodeName();
            }

            if (CANDIDATE_TYPES.contains(localName)) {
                String elementId = normalizeText(element.getAttribute("id"));
                if (StringUtils.hasText(elementId)) {
                    candidates.add(new BpmnIncidentCandidate(
                            fileName,
                            processId,
                            processName,
                            elementId,
                            firstNonBlank(element.getAttribute("name"), elementId),
                            localName,
                            extractJobType(element),
                            normalizeText(element.getAttribute("calledElement"))
                    ));
                }
            }

            collectCandidates(element, fileName, processId, processName, candidates);
        }
    }

    private String extractJobType(Element element) {
        NodeList taskDefinitions = element.getElementsByTagNameNS("*", "taskDefinition");
        for (int index = 0; index < taskDefinitions.getLength(); index++) {
            Node node = taskDefinitions.item(index);
            if (node instanceof Element taskDefinition) {
                String type = normalizeText(taskDefinition.getAttribute("type"));
                if (StringUtils.hasText(type)) {
                    return type;
                }
            }
        }
        return "";
    }

    private List<BpmnIncidentRuleSuggestion> draftSuggestionsWithLlm(List<BpmnProcessSummary> processSummaries,
                                                                     String consultantNotes,
                                                                     List<BpmnIncidentRuleSuggestion> fallbackSuggestions) {
        try {
            ChatClient chatClient = chatClientBuilder
                    .defaultSystem("""
                            You are assisting a Camunda business consultant who is drafting incident-resolution rules from BPMN XML.
                            Return JSON only.
                            Do not explain the answer.
                            Do not invent BPMN elements that are not present in the supplied summaries.
                            Keep rule suggestions conservative and operationally safe.
                            Prefer BLOCKED for called-process deployment/configuration risks.
                            Prefer BY_PROCESS_INSTANCE for likely transient connector or worker infrastructure failures.
                            Use grounded messageContains tokens from the BPMN element name, job type, called element, or process name.
                            """)
                    .build();

            String prompt = buildSuggestionPrompt(processSummaries, consultantNotes);
            String response = chatClient.prompt()
                    .user(prompt)
                    .options(OllamaChatOptions.builder().format("json").build())
                    .call()
                    .content();

            List<Map<String, Object>> parsed = objectMapper.readValue(response, new TypeReference<>() { });
            List<BpmnIncidentRuleSuggestion> suggestions = new ArrayList<>();
            for (Map<String, Object> item : parsed) {
                BpmnIncidentRuleSuggestion suggestion = mapSuggestion(item);
                if (suggestion != null) {
                    suggestions.add(suggestion);
                }
            }

            if (!suggestions.isEmpty()) {
                return suggestions;
            }
        } catch (Exception ex) {
            log.warn("[BPMN RULE DRAFT FALLBACK] Falling back to deterministic suggestion generation: {}", ex.getMessage());
        }
        return fallbackSuggestions;
    }

    private String buildSuggestionPrompt(List<BpmnProcessSummary> processSummaries, String consultantNotes) {
        StringBuilder summary = new StringBuilder();
        for (BpmnProcessSummary process : processSummaries) {
            summary.append("- BPMN file: ").append(process.fileName()).append('\n')
                    .append("  Process ID: ").append(process.processDefinitionId()).append('\n')
                    .append("  Process Name: ").append(process.processName()).append('\n');
            for (BpmnIncidentCandidate candidate : process.candidates()) {
                summary.append("  Candidate: ")
                        .append(candidate.elementType())
                        .append(" | elementId=").append(candidate.elementId())
                        .append(" | elementName=").append(candidate.elementName())
                        .append(" | jobType=").append(blankAsDash(candidate.jobType()))
                        .append(" | calledElement=").append(blankAsDash(candidate.calledElement()))
                        .append('\n');
            }
        }

        return """
                Draft candidate incident-resolution rules for the BPMN inventory below.

                Consultant notes:
                %s

                BPMN candidate summary:
                %s

                Return a JSON array only.
                Each array item must contain these exact fields:
                - source
                - sourceFileName
                - workflowProcessDefinitionId
                - processName
                - elementId
                - elementName
                - elementType
                - jobType
                - calledElement
                - instruction
                - errorTypes
                - httpStatusCodes
                - messageContains
                - resolutionMode
                - reason
                - userFacingGuidance

                Rules:
                - `source` must be `AI`.
                - `errorTypes` must be an array of uppercase Camunda-like identifiers such as `JOB_NO_RETRIES` or `CALLED_ELEMENT_ERROR`.
                - `httpStatusCodes` must be a numeric array.
                - `messageContains` must be lowercase tokens.
                - `resolutionMode` must be one of `BY_PROCESS_INSTANCE`, `BY_INCIDENT_KEY`, `BLOCKED`, or `NO_ACTION`.
                - Return one suggestion per candidate element.
                - Do not include markdown fences.
                """.formatted(
                StringUtils.hasText(consultantNotes) ? consultantNotes.trim() : "No extra notes provided.",
                summary
        );
    }

    private BpmnIncidentRuleSuggestion mapSuggestion(Map<String, Object> item) {
        String workflowProcessDefinitionId = text(item.get("workflowProcessDefinitionId"));
        String elementId = text(item.get("elementId"));
        if (!StringUtils.hasText(workflowProcessDefinitionId) || !StringUtils.hasText(elementId)) {
            return null;
        }

        return new BpmnIncidentRuleSuggestion(
                firstNonBlank(text(item.get("source")), "AI"),
                text(item.get("sourceFileName")),
                workflowProcessDefinitionId,
                text(item.get("processName")),
                elementId,
                text(item.get("elementName")),
                text(item.get("elementType")),
                text(item.get("jobType")),
                text(item.get("calledElement")),
                text(item.get("instruction")),
                normalizeStringList(item.get("errorTypes"), true),
                normalizeIntegerList(item.get("httpStatusCodes")),
                normalizeStringList(item.get("messageContains"), false),
                firstNonBlank(text(item.get("resolutionMode")), "BY_PROCESS_INSTANCE").toUpperCase(Locale.ROOT),
                text(item.get("reason")),
                text(item.get("userFacingGuidance"))
        );
    }

    private List<BpmnIncidentRuleSuggestion> buildFallbackSuggestions(List<BpmnIncidentCandidate> candidates) {
        List<BpmnIncidentRuleSuggestion> suggestions = new ArrayList<>();
        for (BpmnIncidentCandidate candidate : candidates) {
            if ("callActivity".equals(candidate.elementType())) {
                suggestions.add(new BpmnIncidentRuleSuggestion(
                        "FALLBACK",
                        candidate.fileName(),
                        candidate.processDefinitionId(),
                        candidate.processName(),
                        candidate.elementId(),
                        candidate.elementName(),
                        candidate.elementType(),
                        candidate.jobType(),
                        candidate.calledElement(),
                        "Block retry when this call activity indicates a called-process deployment or BPMN configuration mismatch.",
                        List.of("CALLED_ELEMENT_ERROR"),
                        List.of(),
                        distinctTokens(candidate.calledElement(), "called element", candidate.elementName(), candidate.processName()),
                        "BLOCKED",
                        "This BPMN call activity depends on another process deployment. Retry should stay blocked until the called process configuration is verified.",
                        "Review the called process deployment and BPMN called-element mapping before retrying incidents from this activity."
                ));
                continue;
            }

            suggestions.add(new BpmnIncidentRuleSuggestion(
                    "FALLBACK",
                    candidate.fileName(),
                    candidate.processDefinitionId(),
                    candidate.processName(),
                    candidate.elementId(),
                    candidate.elementName(),
                    candidate.elementType(),
                    candidate.jobType(),
                    candidate.calledElement(),
                    "Allow retry for likely transient infrastructure failures on this BPMN worker or connector task.",
                    List.of("JOB_NO_RETRIES"),
                    List.of(500),
                    distinctTokens(candidate.jobType(), candidate.elementName(), candidate.processName()),
                    "BY_PROCESS_INSTANCE",
                    "This BPMN task looks like a worker or connector integration point where transient server-side failures are reasonable retry candidates.",
                    ""
            ));
        }
        return suggestions;
    }

    private Document parseXml(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        return builder.parse(new InputSource(new StringReader(xml)));
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private List<String> normalizeStringList(Object value, boolean uppercase) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                String text = text(item);
                if (!StringUtils.hasText(text)) {
                    continue;
                }
                normalized.add(uppercase ? text.toUpperCase(Locale.ROOT) : text.toLowerCase(Locale.ROOT));
            }
        }
        return List.copyOf(normalized);
    }

    private List<Integer> normalizeIntegerList(Object value) {
        LinkedHashSet<Integer> normalized = new LinkedHashSet<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Number number) {
                    normalized.add(number.intValue());
                    continue;
                }
                String text = text(item);
                if (StringUtils.hasText(text)) {
                    normalized.add(Integer.parseInt(text));
                }
            }
        }
        return List.copyOf(normalized);
    }

    private List<String> distinctTokens(String... rawValues) {
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        for (String rawValue : rawValues) {
            if (!StringUtils.hasText(rawValue)) {
                continue;
            }
            String cleaned = rawValue.trim().toLowerCase(Locale.ROOT);
            tokens.add(cleaned);
        }
        return List.copyOf(tokens);
    }

    private String blankAsDash(String value) {
        return StringUtils.hasText(value) ? value : "-";
    }

    private String normalizeText(String value) {
        return value == null ? "" : value.trim();
    }

    private String firstNonBlank(String first, String fallback) {
        return StringUtils.hasText(first) ? first.trim() : fallback;
    }
}

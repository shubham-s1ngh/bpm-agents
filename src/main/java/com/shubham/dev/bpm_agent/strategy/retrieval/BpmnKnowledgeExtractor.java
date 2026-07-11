package com.shubham.dev.bpm_agent.strategy.retrieval;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Component
public class BpmnKnowledgeExtractor {

    private static final String BPMN_NS = "http://www.omg.org/spec/BPMN/20100524/MODEL";
    private static final String ZEEBE_NS = "http://camunda.org/schema/zeebe/1.0";

    public Map<String, BpmnProcessKnowledge> extractProcesses() {
        Map<String, BpmnProcessKnowledge> processes = new LinkedHashMap<>();
        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources("classpath*:bpmns/*.bpmn");
            for (Resource resource : resources) {
                extractProcesses(resource, processes);
            }
        } catch (Exception ignored) {
            return Map.of();
        }
        return Map.copyOf(processes);
    }

    public Set<String> resolveReachableProcessIds(String rootProcessDefinitionId, Map<String, BpmnProcessKnowledge> processes) {
        if (!StringUtils.hasText(rootProcessDefinitionId) || processes.isEmpty()) {
            return Set.of();
        }

        Set<String> visited = new LinkedHashSet<>();
        ArrayDeque<String> queue = new ArrayDeque<>();
        queue.add(rootProcessDefinitionId);

        while (!queue.isEmpty()) {
            String processId = queue.removeFirst();
            if (!visited.add(processId)) {
                continue;
            }

            BpmnProcessKnowledge process = processes.get(processId);
            if (process == null) {
                continue;
            }

            for (BpmnCallActivityKnowledge callActivity : process.callActivities()) {
                if (StringUtils.hasText(callActivity.calledProcessId())) {
                    queue.addLast(callActivity.calledProcessId());
                }
            }
        }

        return Set.copyOf(visited);
    }

    private void extractProcesses(Resource resource, Map<String, BpmnProcessKnowledge> processes) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        try (InputStream inputStream = resource.getInputStream()) {
            Document document = factory.newDocumentBuilder().parse(inputStream);
            NodeList processNodes = document.getElementsByTagNameNS(BPMN_NS, "process");
            for (int i = 0; i < processNodes.getLength(); i++) {
                Element processElement = (Element) processNodes.item(i);
                BpmnProcessKnowledge processKnowledge = toProcessKnowledge(processElement, resource.getFilename());
                if (StringUtils.hasText(processKnowledge.processId())) {
                    processes.put(processKnowledge.processId(), processKnowledge);
                }
            }
        }
    }

    private BpmnProcessKnowledge toProcessKnowledge(Element processElement, String sourceFile) {
        List<BpmnTaskKnowledge> serviceTasks = new ArrayList<>();
        List<BpmnCallActivityKnowledge> callActivities = new ArrayList<>();
        List<BpmnElementKnowledge> gateways = new ArrayList<>();
        List<BpmnBoundaryEventKnowledge> boundaryEvents = new ArrayList<>();

        NodeList childNodes = processElement.getChildNodes();
        for (int i = 0; i < childNodes.getLength(); i++) {
            Node node = childNodes.item(i);
            if (!(node instanceof Element element) || !BPMN_NS.equals(element.getNamespaceURI())) {
                continue;
            }

            switch (element.getLocalName()) {
                case "serviceTask" -> serviceTasks.add(toServiceTask(element));
                case "callActivity" -> callActivities.add(toCallActivity(element));
                case "exclusiveGateway", "parallelGateway", "eventBasedGateway", "inclusiveGateway" ->
                        gateways.add(new BpmnElementKnowledge(
                                attribute(element, "id"),
                                attribute(element, "name"),
                                element.getLocalName()
                        ));
                case "boundaryEvent" -> boundaryEvents.add(toBoundaryEvent(element));
                default -> {
                }
            }
        }

        return new BpmnProcessKnowledge(
                sourceFile == null ? "unknown.bpmn" : sourceFile,
                attribute(processElement, "id"),
                attribute(processElement, "name"),
                List.copyOf(serviceTasks),
                List.copyOf(callActivities),
                List.copyOf(gateways),
                List.copyOf(boundaryEvents)
        );
    }

    private BpmnTaskKnowledge toServiceTask(Element serviceTaskElement) {
        String taskType = "";
        NodeList extensionElements = serviceTaskElement.getElementsByTagNameNS(ZEEBE_NS, "taskDefinition");
        if (extensionElements.getLength() > 0) {
            taskType = attribute((Element) extensionElements.item(0), "type");
        }
        return new BpmnTaskKnowledge(
                attribute(serviceTaskElement, "id"),
                attribute(serviceTaskElement, "name"),
                taskType
        );
    }

    private BpmnCallActivityKnowledge toCallActivity(Element callActivityElement) {
        String calledProcessId = "";
        NodeList calledElements = callActivityElement.getElementsByTagNameNS(ZEEBE_NS, "calledElement");
        if (calledElements.getLength() > 0) {
            calledProcessId = attribute((Element) calledElements.item(0), "processId");
        }
        return new BpmnCallActivityKnowledge(
                attribute(callActivityElement, "id"),
                attribute(callActivityElement, "name"),
                calledProcessId
        );
    }

    private BpmnBoundaryEventKnowledge toBoundaryEvent(Element boundaryEventElement) {
        return new BpmnBoundaryEventKnowledge(
                attribute(boundaryEventElement, "id"),
                attribute(boundaryEventElement, "name"),
                attribute(boundaryEventElement, "attachedToRef"),
                resolveBoundaryDefinitionType(boundaryEventElement)
        );
    }

    private String resolveBoundaryDefinitionType(Element boundaryEventElement) {
        NodeList childNodes = boundaryEventElement.getChildNodes();
        for (int i = 0; i < childNodes.getLength(); i++) {
            Node node = childNodes.item(i);
            if (!(node instanceof Element element) || !BPMN_NS.equals(element.getNamespaceURI())) {
                continue;
            }
            String localName = element.getLocalName();
            if (localName != null && localName.endsWith("EventDefinition")) {
                return localName;
            }
        }
        return "boundaryEvent";
    }

    private String attribute(Element element, String attributeName) {
        String value = element.getAttribute(attributeName);
        return StringUtils.hasText(value) ? value.trim() : "";
    }

    public record BpmnProcessKnowledge(String sourceFile,
                                       String processId,
                                       String processName,
                                       List<BpmnTaskKnowledge> serviceTasks,
                                       List<BpmnCallActivityKnowledge> callActivities,
                                       List<BpmnElementKnowledge> gateways,
                                       List<BpmnBoundaryEventKnowledge> boundaryEvents) {
    }

    public record BpmnTaskKnowledge(String elementId, String elementName, String taskType) {
    }

    public record BpmnCallActivityKnowledge(String elementId, String elementName, String calledProcessId) {
    }

    public record BpmnElementKnowledge(String elementId, String elementName, String elementType) {
    }

    public record BpmnBoundaryEventKnowledge(String elementId,
                                             String elementName,
                                             String attachedToRef,
                                             String definitionType) {
    }
}

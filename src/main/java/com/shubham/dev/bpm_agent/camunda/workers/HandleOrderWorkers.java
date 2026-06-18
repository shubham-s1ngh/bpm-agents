package com.shubham.dev.bpm_agent.camunda.workers;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.response.ActivatedJob;
import io.camunda.client.api.worker.JobClient;
import jakarta.annotation.PostConstruct;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;



/**
 * Auto-approve worker — handles the "auto-approve-order" and "reject-order" service tasks after the
 * gateway routing decision.
 */

@Component

public class HandleOrderWorkers {
  private final Logger log = LoggerFactory.getLogger(this.getClass());
  private final AgentWorkerFactory factory;
    private final CamundaClient camundaClient;


    public HandleOrderWorkers(AgentWorkerFactory factory, CamundaClient camundaClient) {
        this.factory = factory;
        this.camundaClient = camundaClient;
    }

    @PostConstruct
  public void register() {
    factory.register("send_message", this::sendMessage);
    factory.register("reject-order", this::handleReject);

  }

  private void sendMessage(JobClient client, ActivatedJob job) {
    String orderId = (String) job.getVariablesAsMap().get("orderId");
      log.info("✅ [AutoApprove] Order APPROVED: {}", orderId);
    camundaClient
            .newPublishMessageCommand()
            .messageName("msg_regular")
            .correlationKey(orderId)
            .variables(job.getVariablesAsMap())
            .send()
            .thenApply(
                    response -> {
                      log.info(
                              "notifyCamundaMessage successfully published for orderId   {} and response {} ", orderId, response.toString()
                      );
                      return response;
                    })
            .exceptionally(
                    t -> {
                      log.info("Could not publish notifyCamundaMessage for caseId {}:", t.getMessage());
                      throw new RuntimeException(
                              "Could not publish notifyCamundaMessage for caseId : " + t.getMessage(), t);
                    });

      client
              .newCompleteCommand(job.getKey())
              .send()
              .join();

  }

  private void handleReject(JobClient client, ActivatedJob job) {
    String orderId = (String) job.getVariablesAsMap().get("orderId");
    log.info("❌ [RejectOrder] Order REJECTED: {}");
    client
        .newCompleteCommand(job.getKey())
        .variables(java.util.Map.of("finalStatus", "REJECTED"))
        .send()
        .join();
  }


}

package com.shubham.dev.bpm_agent.camunda.workers;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.response.ActivatedJob;
import io.camunda.client.api.worker.JobClient;
import io.camunda.client.api.worker.JobWorker;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * Factory for programmatic Camunda job worker registration.
 *
 * <p>Bypasses @JobWorker annotation processing (which has a dispatch bug in
 * camunda-spring-boot-3-starter 8.9.0 with REST polling).
 *
 * <p>Usage: factory.register("my-job-type", this::myHandler);
 */
@Component
public class AgentWorkerFactory {

  private static final Logger log = LoggerFactory.getLogger(AgentWorkerFactory.class);

  private final CamundaClient camundaClient;
  private final List<JobWorker> workers = new ArrayList<>();

  @Value("${camunda.client.worker.defaults.max-jobs-active:10}")
  private int maxJobsActive;

  @Value("${camunda.client.worker.defaults.timeout:PT1M}")
  private String timeout;

  public AgentWorkerFactory(CamundaClient camundaClient) {
    this.camundaClient = camundaClient;
  }

  /**
   * Register a job worker programmatically.
   *
   * @param jobType The Zeebe job type (must match BPMN service task type)
   * @param handler BiConsumer<JobClient, ActivatedJob> — your handler logic
   */
  public void register(String jobType, BiConsumer<JobClient, ActivatedJob> handler) {
    log.info("[AgentWorkerFactory] Registering worker for job type: {}", jobType);

    JobWorker worker =
        camundaClient
            .newWorker()
            .jobType(jobType)
            .handler(handler::accept)
            .timeout(Duration.parse(timeout))
            .maxJobsActive(maxJobsActive)
            .open();

    workers.add(worker);
    log.info(
        "[AgentWorkerFactory] Worker registered for type: {} | open={}",
        jobType,
        !worker.isClosed());
  }

  @PreDestroy
  public void closeAll() {
    log.info("[AgentWorkerFactory] Closing {} worker(s)...", workers.size());
    workers.forEach(
        w -> {
          if (!w.isClosed()) w.close();
        });
  }
}

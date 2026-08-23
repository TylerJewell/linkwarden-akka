package io.akka.linkwarden.application;

import akka.Done;
import akka.javasdk.annotations.Component;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.workflow.Workflow;
import com.typesafe.config.Config;
import io.akka.linkwarden.domain.RetryPolicy;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handing one link to the search index. SPEC-001 R17, R21.
 *
 * <p>The index itself is out of scope (SPEC-001 §4 C), so {@code indexFails} on the command stands
 * where the engine's answer would. What is in scope is what happens afterwards: the source
 * swallows a failed index task and writes the current index version anyway, so nothing was indexed
 * and nothing will be offered again; here the version is written only on the succeeding path.
 */
@Component(id = "link-index")
public class LinkIndexWorkflow extends Workflow<LinkIndexWorkflow.Indexing> {

  public record Indexing(
      String linkId, boolean indexFails, int attemptsMade, boolean indexed, boolean finished) {}

  public record Start(String linkId, boolean indexFails) {}

  private static final Logger logger = LoggerFactory.getLogger(LinkIndexWorkflow.class);

  private final ComponentClient client;
  private final RetryPolicy policy;

  public LinkIndexWorkflow(ComponentClient client, Config config) {
    this.client = client;
    this.policy =
        new RetryPolicy(
            config.hasPath("linkwarden.retry.max-attempts")
                ? config.getInt("linkwarden.retry.max-attempts")
                : RetryPolicy.DEFAULT.maxAttempts(),
            config.hasPath("linkwarden.retry.base-delay")
                ? config.getDuration("linkwarden.retry.base-delay")
                : RetryPolicy.DEFAULT.baseDelay());
  }

  public Effect<Done> start(Start cmd) {
    return effects()
        .updateState(new Indexing(cmd.linkId(), cmd.indexFails(), 0, false, false))
        .transitionTo(LinkIndexWorkflow::index)
        .thenReply(Done.getInstance());
  }

  public ReadOnlyEffect<Indexing> state() {
    return effects().reply(currentState());
  }

  public StepEffect index() {
    var state = currentState();
    int attemptsMade = state.attemptsMade() + 1;

    if (!state.indexFails()) {
      client.forEventSourcedEntity(state.linkId()).method(LinkEntity::markIndexed).invoke();
      return stepEffects()
          .updateState(new Indexing(state.linkId(), false, attemptsMade, true, true))
          .thenEnd();
    }

    if (policy.hasAnotherAttempt(attemptsMade)) {
      Duration wait = policy.delayBefore(attemptsMade + 1);
      logger.info("link {} index attempt {} failed; next attempt in {}", state.linkId(), attemptsMade, wait);
      return stepEffects()
          .updateState(new Indexing(state.linkId(), true, attemptsMade, false, false))
          .thenTransitionTo(LinkIndexWorkflow::waitToRetry)
          .withInput(wait);
    }

    // R21 — giving up leaves the version where it was, so R17 offers the link again.
    logger.info("link {} not indexed after {} attempts; left for the next pass", state.linkId(), attemptsMade);
    return stepEffects()
        .updateState(new Indexing(state.linkId(), true, attemptsMade, false, true))
        .thenEnd();
  }

  public StepEffect waitToRetry(Duration wait) {
    timers()
        .createSingleTimer(
            "index-retry-" + currentState().linkId(),
            wait,
            client
                .forWorkflow(commandContext().workflowId())
                .method(LinkIndexWorkflow::retryNow)
                .deferred());
    return stepEffects().thenPause();
  }

  public Effect<Done> retryNow() {
    return effects().transitionTo(LinkIndexWorkflow::index).thenReply(Done.getInstance());
  }
}

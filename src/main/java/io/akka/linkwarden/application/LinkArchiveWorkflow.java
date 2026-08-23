package io.akka.linkwarden.application;

import akka.Done;
import akka.javasdk.annotations.Component;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.workflow.Workflow;
import com.typesafe.config.Config;
import io.akka.linkwarden.domain.PageFacts;
import io.akka.linkwarden.domain.RetryPolicy;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One link's archiving attempt, and its retries. SPEC-001 R10, R14-R16, R20.
 *
 * <p>The retry is the port's own rule, not the source's (SPEC-001 Â§4 A): the source marks a link
 * preserved in the same block that reports the failure, so a link that failed is indistinguishable
 * afterwards from one that had nothing to preserve. Here the attempt is retried up to the policy's
 * limit and only the exhausted case marks the link, which means the marking in {@code finish} is
 * reached by both routes and is the same write either way.
 */
@Component(id = "link-archive")
public class LinkArchiveWorkflow extends Workflow<LinkArchiveWorkflow.Attempting> {

  /**
   * @param attemptsMade how many attempts have already run and failed. The wait before the next
   *     one is derived from this rather than stored, so a resumed workflow computes the same wait
   *     the interrupted one would have.
   */
  public record Attempting(
      String linkId, PageFacts facts, int attemptsMade, String lastFailure, boolean finished) {}

  public record Start(String linkId, PageFacts facts) {}

  private static final Logger logger = LoggerFactory.getLogger(LinkArchiveWorkflow.class);

  private final ComponentClient client;
  private final RetryPolicy policy;

  public LinkArchiveWorkflow(ComponentClient client, Config config) {
    this.client = client;
    // The base wait is configuration so that a test can exercise the real retry path in a
    // reasonable time; the arithmetic over it is the same code either way.
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
        .updateState(new Attempting(cmd.linkId(), cmd.facts(), 0, null, false))
        .transitionTo(LinkArchiveWorkflow::attempt)
        .thenReply(Done.getInstance());
  }

  public ReadOnlyEffect<Attempting> state() {
    return effects().reply(currentState());
  }

  public StepEffect attempt() {
    var state = currentState();
    var status = client.forEventSourcedEntity(state.linkId()).method(LinkEntity::status).invoke();

    // R16 — a link that is already gone is not attempted at all; finish is where the removal
    // is decided, and it decides the same way whether the link went before or during.
    if (status.link().deleted()) {
      return stepEffects().thenTransitionTo(LinkArchiveWorkflow::finish);
    }

    var outcome = AttemptRunner.run(status.link(), state.facts());

    for (AttemptRunner.Write write : outcome.writes()) {
      switch (write) {
        case AttemptRunner.Write.Type w ->
            client
                .forEventSourcedEntity(state.linkId())
                .method(LinkEntity::determineType)
                .invoke(w.type());
        case AttemptRunner.Write.Meta w ->
            client
                .forEventSourcedEntity(state.linkId())
                .method(LinkEntity::setMetaDescription)
                .invoke(w.description());
        case AttemptRunner.Write.Text w ->
            client
                .forEventSourcedEntity(state.linkId())
                .method(LinkEntity::extractText)
                .invoke(w.text());
        case AttemptRunner.Write.Preserved w ->
            client
                .forEventSourcedEntity(state.linkId())
                .method(LinkEntity::preserveFormat)
                .invoke(new LinkEntity.Preserve(w.format(), w.path()));
      }
    }

    int attemptsMade = state.attemptsMade() + 1;

    if (outcome.failedAfter() == null) {
      return stepEffects()
          .updateState(new Attempting(state.linkId(), state.facts(), attemptsMade, null, false))
          .thenTransitionTo(LinkArchiveWorkflow::finish);
    }

    // R20 — a failure keeps the link unmarked while attempts remain, which is the whole of the
    // difference from the source: there, the marking happens on the way out of the failure.
    if (policy.hasAnotherAttempt(attemptsMade)) {
      Duration wait = policy.delayBefore(attemptsMade + 1);
      logger.info(
          "link {} attempt {} failed after {}; next attempt in {}",
          state.linkId(),
          attemptsMade,
          outcome.failedAfter(),
          wait);
      return stepEffects()
          .updateState(
              new Attempting(
                  state.linkId(), state.facts(), attemptsMade, outcome.failedAfter(), false))
          .thenTransitionTo(LinkArchiveWorkflow::waitToRetry)
          .withInput(wait);
    }

    logger.info(
        "link {} gave up after {} attempts, last failure {}",
        state.linkId(),
        attemptsMade,
        outcome.failedAfter());
    return stepEffects()
        .updateState(
            new Attempting(
                state.linkId(), state.facts(), attemptsMade, outcome.failedAfter(), false))
        .thenTransitionTo(LinkArchiveWorkflow::finish);
  }

  public StepEffect waitToRetry(Duration wait) {
    timers()
        .createSingleTimer(
            "retry-" + currentState().linkId(),
            wait,
            client
                .forWorkflow(commandContext().workflowId())
                .method(LinkArchiveWorkflow::retryNow)
                .deferred());
    return stepEffects().thenPause();
  }

  public Effect<Done> retryNow() {
    return effects().transitionTo(LinkArchiveWorkflow::attempt).thenReply(Done.getInstance());
  }

  /**
   * R15 and R16. The entity answers whether the link was still there; a link that was not has its
   * files removed instead of being marked.
   */
  public StepEffect finish() {
    var state = currentState();
    boolean stillThere =
        client
            .forEventSourcedEntity(state.linkId())
            .method(LinkEntity::finishAttempt)
            .invoke(Instant.now());
    if (!stillThere) {
      logger.info("link {} was deleted while it was being archived; files removed", state.linkId());
    }
    return stepEffects()
        .updateState(
            new Attempting(
                state.linkId(), state.facts(), state.attemptsMade(), state.lastFailure(), true))
        .thenEnd();
  }
}

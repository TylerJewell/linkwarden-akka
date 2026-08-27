package io.akka.linkwarden.application;

import static java.time.Duration.ofSeconds;

import akka.Done;
import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.StepName;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.workflow.Workflow;
import io.akka.linkwarden.domain.ArchivalSettings;
import io.akka.linkwarden.domain.ArchivalSettingsResolver;
import io.akka.linkwarden.domain.AttemptSubject;
import io.akka.linkwarden.domain.Ids;
import io.akka.linkwarden.domain.LinkType;
import io.akka.linkwarden.domain.PageFacts;
import io.akka.linkwarden.domain.PreservedFormats;
import io.akka.linkwarden.domain.Records;
import io.akka.linkwarden.domain.RetryPolicy;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * One link, preserved. SPEC-001 R48–R56, and open decision A.
 *
 * <p>The original writes its finishing instant on the failing path as well as the succeeding one,
 * which takes a failed link out of the selector for good. This port makes three further attempts,
 * five, ten and twenty seconds apart, and then writes the same terminal state — so a caller
 * watching only the end sees what the original produces, and one watching the middle sees more.
 */
@Component(id = "link-archive")
public class LinkArchiveWorkflow extends Workflow<LinkArchiveWorkflow.State> {

  /** @param attempt how many attempts have been made, counting from one */
  public record State(int linkId, int attempt, String status, String failedAfter) {}

  private final ComponentClient componentClient;
  private final Fetcher fetcher;
  private final Data data;

  public LinkArchiveWorkflow(
      ComponentClient componentClient, Fetcher fetcher, Data data) {
    this.componentClient = componentClient;
    this.fetcher = fetcher;
    this.data = data;
  }

  @Override
  public WorkflowSettings settings() {
    return WorkflowSettings.builder().defaultStepTimeout(ofSeconds(60)).build();
  }

  /**
   * Starts one run.
   *
   * <p>A workflow instance is one run rather than one link: a completed workflow cannot be
   * transitioned again, so a link asked to be preserved a second time gets an instance of its own
   * and this refuses only a second start of the same one.
   */
  public Effect<String> start(Integer linkId) {
    if (currentState() != null) return effects().reply("already running");
    return effects()
        .updateState(new State(linkId, 0, "running", null))
        .transitionTo(LinkArchiveWorkflow::attemptStep)
        .thenReply("started");
  }

  public ReadOnlyEffect<State> status() {
    if (currentState() == null) return effects().error("Nothing archived under this identifier.");
    return effects().reply(currentState());
  }

  /** The handler the pause between attempts hands back to. */
  public Effect<Done> resume() {
    return effects()
        .transitionTo(LinkArchiveWorkflow::attemptStep)
        .thenReply(Done.getInstance());
  }

  @StepName("attempt")
  private StepEffect attemptStep() {
    State state = currentState();
    int attempt = state.attempt() + 1;
    Optional<Records.Link> found = data.link(state.linkId());
    if (found.isEmpty()) {
      return stepEffects().updateState(new State(state.linkId(), attempt, "done", "gone")).thenEnd();
    }
    Records.Link link = found.get();
    Instant now = Instant.now();

    ArchivalSettings settings = settingsFor(link);
    AttemptSubject subject =
        AttemptSubject.of(
            link.id(),
            link.collectionId(),
            link.url(),
            new PreservedFormats(
                link.image(), link.pdf(), link.readable(), link.monolith(), link.preview()),
            settings);
    PageFacts facts = fetcher.facts(link.url());
    AttemptRunner.Outcome outcome = AttemptRunner.run(subject, facts);

    for (AttemptRunner.Write write : outcome.writes()) {
      apply(link.id(), write, now);
    }

    if (outcome.failedAfter() != null && attempt < RetryPolicy.DEFAULT.maxAttempts()) {
      // Five, ten, then twenty seconds. Each attempt starts from whatever the last one managed
      // to write, so a run that got as far as the screenshot does not take it again.
      Duration wait =
          RetryPolicy.DEFAULT.baseDelay().multipliedBy(1L << (attempt - 1));
      return stepEffects()
          .updateState(new State(state.linkId(), attempt, "retrying", outcome.failedAfter()))
          .thenPause(
              pauseSetting(wait)
                  .reason("waiting " + wait.toSeconds() + "s before attempt " + (attempt + 1))
                  .timeoutHandler(LinkArchiveWorkflow::resume));
    }

    // R53 — the finishing instant is written whatever the outcome, and every format still
    // absent reads unavailable, so a link is never offered to the pipeline twice.
    componentClient
        .forKeyValueEntity(Ids.link(link.id()))
        .method(LinkEntity::finishPreservation)
        .invoke(new LinkEntity.Finish(now));
    return stepEffects()
        .updateState(new State(state.linkId(), attempt, "done", outcome.failedAfter()))
        .thenEnd();
  }

  /** SPEC-001 R50 — the union of the link's archival tags, or the owner's own five settings. */
  private ArchivalSettings settingsFor(Records.Link link) {
    Records.User owner =
        data.collection(link.collectionId())
            .flatMap(collection -> data.user(collection.ownerId()))
            .orElse(null);
    ArchivalSettings ownerSettings =
        owner == null ? ArchivalSettings.NONE : owner.archivalSettings();
    return ArchivalSettingsResolver.resolve(
        data.tagsOf(link).stream().map(Records.Tag::asArchivalTag).toList(), ownerSettings);
  }

  private void apply(int linkId, AttemptRunner.Write write, Instant now) {
    switch (write) {
      case AttemptRunner.Write.Type type ->
          componentClient
              .forKeyValueEntity(Ids.link(linkId))
              .method(LinkEntity::setType)
              .invoke(new LinkEntity.SetType(nameOf(type.type()), now));
      case AttemptRunner.Write.Meta meta ->
          componentClient
              .forKeyValueEntity(Ids.link(linkId))
              .method(LinkEntity::setText)
              .invoke(new LinkEntity.SetText(meta.description(), null, now));
      case AttemptRunner.Write.Text text ->
          componentClient
              .forKeyValueEntity(Ids.link(linkId))
              .method(LinkEntity::setText)
              .invoke(new LinkEntity.SetText(null, text.text(), now));
      case AttemptRunner.Write.Preserved preserved ->
          componentClient
              .forKeyValueEntity(Ids.link(linkId))
              .method(LinkEntity::preserve)
              .invoke(new LinkEntity.Preserve(preserved.format(), preserved.path(), now));
    }
  }

  private static String nameOf(LinkType type) {
    return switch (type) {
      case PDF -> "pdf";
      case IMAGE -> "image";
      case URL -> "url";
    };
  }
}

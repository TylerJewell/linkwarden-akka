package io.akka.linkwarden.application;

import akka.Done;
import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.TypeName;
import akka.javasdk.eventsourcedentity.EventSourcedEntity;
import io.akka.linkwarden.domain.ArchivalSettings;
import io.akka.linkwarden.domain.AttemptCompletion;
import io.akka.linkwarden.domain.Eligibility;
import io.akka.linkwarden.domain.Format;
import io.akka.linkwarden.domain.Link;
import io.akka.linkwarden.domain.LinkType;
import io.akka.linkwarden.domain.MetaDescription;
import io.akka.linkwarden.domain.PreservedFormats;
import io.akka.linkwarden.domain.Tag;
import java.time.Instant;
import java.util.List;

/**
 * One link's own preservation state. SPEC-001 §2 and R6, R12, R13, R15, R16, R19.
 *
 * <p>The entity holds the answers; {@link LinkArchiveWorkflow} decides the order they are asked
 * in. Splitting it that way is what lets a failed attempt be retried without the link's own
 * history recording an attempt that wrote nothing.
 */
@Component(id = "link")
public class LinkEntity extends EventSourcedEntity<Link, LinkEntity.Event> {

  public sealed interface Event {}

  @TypeName("link-saved")
  public record Saved(
      String linkId,
      String title,
      String url,
      String collectionId,
      String ownerId,
      List<Tag> tags,
      ArchivalSettings ownerSettings,
      Instant at)
      implements Event {}

  @TypeName("link-type-determined")
  public record TypeDetermined(LinkType type) implements Event {}

  @TypeName("link-meta-description-set")
  public record MetaDescriptionSet(String metaDescription) implements Event {}

  @TypeName("link-text-extracted")
  public record TextExtracted(String textContent) implements Event {}

  @TypeName("link-format-preserved")
  public record FormatPreserved(Format format, String path) implements Event {}

  @TypeName("link-preservation-finished")
  public record PreservationFinished(PreservedFormats formats, Instant at) implements Event {}

  @TypeName("link-indexed")
  public record Indexed(int indexVersion) implements Event {}

  @TypeName("link-rearchive-requested")
  public record ReArchiveRequested(Instant at) implements Event {}

  @TypeName("link-deleted")
  public record Deleted(Instant at) implements Event {}

  public record Save(
      String title,
      String url,
      String collectionId,
      String ownerId,
      List<Tag> tags,
      ArchivalSettings ownerSettings,
      Instant now) {}

  public record Preserve(Format format, String path) {}

  public record Status(Link link, boolean awaitingPreservation, boolean awaitingIndexing) {}

  private final String linkId;

  public LinkEntity(akka.javasdk.eventsourcedentity.EventSourcedEntityContext context) {
    this.linkId = context.entityId();
  }

  public Effect<Done> save(Save cmd) {
    if (currentState() != null && !currentState().deleted()) {
      return effects().error("link " + linkId + " already exists");
    }
    return effects()
        .persist(
            new Saved(
                linkId,
                cmd.title(),
                cmd.url(),
                cmd.collectionId(),
                cmd.ownerId(),
                cmd.tags() == null ? List.of() : cmd.tags(),
                cmd.ownerSettings(),
                cmd.now()))
        .thenReply(s -> Done.getInstance());
  }

  public Effect<Done> determineType(LinkType type) {
    return requireLive(() -> effects().persist(new TypeDetermined(type)).thenReply(s -> Done.getInstance()));
  }

  /** R12 — the description is clipped here so that every route into it clips it the same way. */
  public Effect<Done> setMetaDescription(String raw) {
    String clipped = MetaDescription.clip(raw);
    if (clipped == null || clipped.isEmpty()) {
      return effects().reply(Done.getInstance());
    }
    return requireLive(
        () -> effects().persist(new MetaDescriptionSet(clipped)).thenReply(s -> Done.getInstance()));
  }

  /** R13 — an empty extraction writes neither the text nor the readable path. */
  public Effect<Done> extractText(String text) {
    if (text == null || text.isEmpty()) {
      return effects().reply(Done.getInstance());
    }
    return requireLive(
        () -> effects().persist(new TextExtracted(text)).thenReply(s -> Done.getInstance()));
  }

  public Effect<Done> preserveFormat(Preserve cmd) {
    return requireLive(
        () ->
            effects()
                .persist(new FormatPreserved(cmd.format(), cmd.path()))
                .thenReply(s -> Done.getInstance()));
  }

  /**
   * R15 and R16 — the one call every attempt ends with, succeeding or failing. A link deleted
   * while it was being archived writes no preservation state; the caller reads the reply to know
   * it must remove the link's files instead.
   */
  public Effect<Boolean> finishAttempt(Instant now) {
    if (AttemptCompletion.decide(currentState()) == AttemptCompletion.Outcome.REMOVE_FILES) {
      return effects().reply(false);
    }
    return effects()
        .persist(new PreservationFinished(currentState().formats().markAbsentUnavailable(), now))
        .thenReply(s -> true);
  }

  /** R21 — only a succeeding index attempt advances the version. */
  public Effect<Done> markIndexed() {
    return requireLive(
        () ->
            effects()
                .persist(new Indexed(Eligibility.CURRENT_INDEX_VERSION))
                .thenReply(s -> Done.getInstance()));
  }

  /** R19 — one write re-opens both pipelines. */
  public Effect<Done> requestReArchive(Instant now) {
    return requireLive(
        () -> effects().persist(new ReArchiveRequested(now)).thenReply(s -> Done.getInstance()));
  }

  public Effect<Done> delete(Instant now) {
    return requireLive(() -> effects().persist(new Deleted(now)).thenReply(s -> Done.getInstance()));
  }

  public ReadOnlyEffect<Status> status() {
    if (currentState() == null) {
      return effects().error("link " + linkId + " not found");
    }
    var link = currentState();
    var candidate =
        new io.akka.linkwarden.domain.Candidate(
            link.linkId(),
            link.ownerId(),
            link.url(),
            link.createdAt(),
            link.lastPreserved(),
            null,
            link.indexVersion());
    return effects()
        .reply(
            new Status(
                link,
                !link.deleted() && Eligibility.awaitingPreservation(candidate),
                !link.deleted() && Eligibility.awaitingIndexing(candidate)));
  }

  private <T> Effect<T> requireLive(java.util.function.Supplier<Effect<T>> body) {
    if (currentState() == null) {
      return effects().error("link " + linkId + " not found");
    }
    if (currentState().deleted()) {
      return effects().error("link " + linkId + " has been deleted");
    }
    return body.get();
  }

  @Override
  public Link applyEvent(Event event) {
    return switch (event) {
      case Saved e ->
          Link.saved(
              e.linkId(), e.title(), e.url(), e.collectionId(), e.ownerId(), e.tags(),
              e.ownerSettings(), e.at());
      case TypeDetermined e -> currentState().withType(e.type());
      case MetaDescriptionSet e -> currentState().withMetaDescription(e.metaDescription());
      case TextExtracted e -> currentState().withTextContent(e.textContent());
      case FormatPreserved e ->
          currentState().withFormats(currentState().formats().with(e.format(), e.path()));
      case PreservationFinished e ->
          currentState().withFormats(e.formats()).withLastPreserved(e.at()).withIndexVersion(null);
      case Indexed e -> currentState().withIndexVersion(e.indexVersion());
      case ReArchiveRequested e ->
          currentState()
              .withFormats(PreservedFormats.EMPTY)
              .withTextContent(null)
              .withLastPreserved(null)
              .withIndexVersion(null);
      case Deleted e -> currentState().deletedNow();
    };
  }
}

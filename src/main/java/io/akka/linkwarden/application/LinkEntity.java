package io.akka.linkwarden.application;

import akka.Done;
import akka.javasdk.annotations.Component;
import akka.javasdk.keyvalueentity.KeyValueEntity;
import io.akka.linkwarden.domain.Eligibility;
import io.akka.linkwarden.domain.Format;
import io.akka.linkwarden.domain.Records;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** One saved link and everything written back about it. SPEC-001 R33–R47, R48–R59. */
@Component(id = "link")
public class LinkEntity extends KeyValueEntity<Records.Link> {

  public record Create(
      int id,
      String name,
      String type,
      String description,
      int collectionId,
      Integer createdById,
      String url,
      List<Integer> tagIds,
      Instant importDate,
      /** SPEC-001 R39 — a url the guard refuses is finished before it is ever offered. */
      boolean unfetchable,
      String image,
      String textContent,
      Instant now) {}

  /** The body of an update, with {@code null} meaning "leave it". */
  public record Update(
      String name,
      String url,
      String description,
      String icon,
      String iconWeight,
      String color,
      Integer collectionId,
      List<Integer> tagIds,
      boolean clearPreservation,
      Instant now) {}

  public record Pin(int userId, boolean pinned, Instant now) {}

  public record Preserve(Format format, String path, Instant now) {}

  public record Finish(Instant now) {}

  public record SetIndexVersion(Integer indexVersion, Instant now) {}

  public record SetText(String metaDescription, String textContent, Instant now) {}

  public record SetType(String type, Instant now) {}

  public record Uploaded(
      String field, String path, boolean pdf, Instant now) {}

  public Effect<Done> create(Create cmd) {
    if (currentState() != null && !currentState().deleted()) {
      return effects().error("Link already exists.");
    }
    Records.Link link =
        Records.Link.fresh(
            cmd.id(), cmd.name(), cmd.type(), cmd.description(), cmd.collectionId(),
            cmd.createdById(), cmd.url(), cmd.tagIds(), cmd.importDate(), cmd.now());
    if (cmd.textContent() != null) {
      Records.Link.Builder b = link.copy();
      b.textContent = cmd.textContent();
      link = b.build();
    }
    if (cmd.image() != null && !cmd.image().isEmpty()) {
      Records.Link.Builder b = link.copy();
      b.image = cmd.image();
      link = b.build();
    }
    if (cmd.unfetchable()) link = link.unfetchable(cmd.now());
    return effects().updateState(link).thenReply(Done.getInstance());
  }

  public ReadOnlyEffect<Records.Link> get() {
    Records.Link link = currentState();
    if (link == null || link.deleted()) return effects().error("Link not found.");
    return effects().reply(link);
  }

  public Effect<Records.Link> update(Update cmd) {
    Records.Link link = currentState();
    if (link == null || link.deleted()) return effects().error("Link not found.");

    Records.Link.Builder b = link.copy();
    b.name = cmd.name() == null ? "" : cmd.name();
    if (cmd.url() != null) b.url = cmd.url();
    b.description = cmd.description() == null ? "" : cmd.description();
    b.icon = cmd.icon();
    b.iconWeight = cmd.iconWeight();
    b.color = cmd.color();
    if (cmd.collectionId() != null) b.collectionId = cmd.collectionId();
    if (cmd.tagIds() != null) b.tagIds = List.copyOf(cmd.tagIds());
    if (cmd.clearPreservation()) {
      b.image = null;
      b.pdf = null;
      b.readable = null;
      b.monolith = null;
      b.preview = null;
      b.lastPreserved = null;
    }
    // SPEC-001 R43 — cleared on every update, whether or not the url moved.
    b.indexVersion = null;
    b.updatedAt = cmd.now();

    Records.Link updated = b.build();
    return effects().updateState(updated).thenReply(updated);
  }

  public Effect<Records.Link> pin(Pin cmd) {
    Records.Link link = currentState();
    if (link == null || link.deleted()) return effects().error("Link not found.");
    List<Integer> pinned = new ArrayList<>(link.pinnedBy());
    if (cmd.pinned()) {
      if (!pinned.contains(cmd.userId())) pinned.add(cmd.userId());
    } else {
      pinned.removeIf(id -> id == cmd.userId());
    }
    Records.Link.Builder b = link.copy();
    b.pinnedBy = List.copyOf(pinned);
    b.updatedAt = cmd.now();
    Records.Link updated = b.build();
    return effects().updateState(updated).thenReply(updated);
  }

  public Effect<Done> setTags(List<Integer> tagIds) {
    Records.Link link = currentState();
    if (link == null || link.deleted()) return effects().error("Link not found.");
    Records.Link.Builder b = link.copy();
    b.tagIds = List.copyOf(tagIds);
    b.indexVersion = null;
    return effects().updateState(b.build()).thenReply(Done.getInstance());
  }

  public Effect<Done> preserve(Preserve cmd) {
    Records.Link link = currentState();
    if (link == null || link.deleted()) return effects().error("Link not found.");
    Records.Link.Builder b = link.copy();
    switch (cmd.format()) {
      case IMAGE -> b.image = cmd.path();
      case PDF -> b.pdf = cmd.path();
      case READABLE -> b.readable = cmd.path();
      case MONOLITH -> b.monolith = cmd.path();
      case PREVIEW -> b.preview = cmd.path();
    }
    b.updatedAt = cmd.now();
    return effects().updateState(b.build()).thenReply(Done.getInstance());
  }

  /**
   * SPEC-001 R52–R53 — the end of an attempt: every format still absent reads unavailable, the
   * finishing instant is written whatever the outcome, and the link is offered to the index.
   */
  public Effect<Done> finishPreservation(Finish cmd) {
    Records.Link link = currentState();
    if (link == null || link.deleted()) return effects().error("Link not found.");
    Records.Link.Builder b = link.copy();
    if (b.image == null) b.image = Records.Link.UNAVAILABLE;
    if (b.pdf == null) b.pdf = Records.Link.UNAVAILABLE;
    if (b.readable == null) b.readable = Records.Link.UNAVAILABLE;
    if (b.monolith == null) b.monolith = Records.Link.UNAVAILABLE;
    if (b.preview == null) b.preview = Records.Link.UNAVAILABLE;
    b.lastPreserved = cmd.now();
    b.indexVersion = null;
    b.updatedAt = cmd.now();
    return effects().updateState(b.build()).thenReply(Done.getInstance());
  }

  public Effect<Done> setIndexVersion(SetIndexVersion cmd) {
    Records.Link link = currentState();
    if (link == null || link.deleted()) return effects().error("Link not found.");
    Records.Link.Builder b = link.copy();
    b.indexVersion = cmd.indexVersion();
    b.updatedAt = cmd.now();
    return effects().updateState(b.build()).thenReply(Done.getInstance());
  }

  public Effect<Done> setText(SetText cmd) {
    Records.Link link = currentState();
    if (link == null || link.deleted()) return effects().error("Link not found.");
    Records.Link.Builder b = link.copy();
    if (cmd.metaDescription() != null) b.metaDescription = cmd.metaDescription();
    if (cmd.textContent() != null) b.textContent = cmd.textContent();
    b.updatedAt = cmd.now();
    return effects().updateState(b.build()).thenReply(Done.getInstance());
  }

  public Effect<Done> setType(SetType cmd) {
    Records.Link link = currentState();
    if (link == null || link.deleted()) return effects().error("Link not found.");
    Records.Link.Builder b = link.copy();
    b.type = cmd.type();
    b.updatedAt = cmd.now();
    return effects().updateState(b.build()).thenReply(Done.getInstance());
  }

  /** SPEC-001 R89 — a file a caller supplied, which is not a preservation attempt. */
  public Effect<Records.Link> uploaded(Uploaded cmd) {
    Records.Link link = currentState();
    if (link == null || link.deleted()) return effects().error("Link not found.");
    Records.Link.Builder b = link.copy();
    switch (cmd.field()) {
      case "image" -> b.image = cmd.path();
      case "pdf" -> b.pdf = cmd.path();
      case "monolith" -> b.monolith = cmd.path();
      default -> {
        // a preview upload writes no format field of its own
      }
    }
    if (cmd.pdf()) b.preview = Records.Link.UNAVAILABLE;
    b.clientSide = true;
    b.updatedAt = cmd.now();
    Records.Link updated = b.build();
    return effects().updateState(updated).thenReply(updated);
  }

  /** SPEC-001 R54 — asked to be preserved again: everything an attempt wrote is cleared. */
  public Effect<Done> rePreserve(Instant now) {
    Records.Link link = currentState();
    if (link == null || link.deleted()) return effects().error("Link not found.");
    Records.Link.Builder b = link.withPreservationCleared(now).copy();
    b.clientSide = false;
    return effects().updateState(b.build()).thenReply(Done.getInstance());
  }

  /**
   * SPEC-001 R56 — clears only the formats that failed and are wanted.
   *
   * <p>A link none of whose broken formats is wanted is left exactly as it was, which is what
   * keeps a second run of the administrator's repair from re-queueing everything.
   */
  public record RepairBroken(io.akka.linkwarden.domain.ArchivalSettings wanted, Instant now) {}

  public Effect<Done> repairBroken(RepairBroken cmd) {
    io.akka.linkwarden.domain.ArchivalSettings wanted = cmd.wanted();
    Instant now = cmd.now();
    Records.Link link = currentState();
    if (link == null || link.deleted()) return effects().error("Link not found.");

    boolean image = Records.Link.UNAVAILABLE.equals(link.image()) && wanted.archiveAsScreenshot();
    boolean pdf = Records.Link.UNAVAILABLE.equals(link.pdf()) && wanted.archiveAsPDF();
    boolean readable = Records.Link.UNAVAILABLE.equals(link.readable()) && wanted.archiveAsReadable();
    boolean monolith = Records.Link.UNAVAILABLE.equals(link.monolith()) && wanted.archiveAsMonolith();

    if (!(image || pdf || readable || monolith)) return effects().reply(Done.getInstance());

    Records.Link.Builder b = link.copy();
    if (image) b.image = null;
    if (pdf) b.pdf = null;
    if (readable) b.readable = null;
    if (monolith) b.monolith = null;
    b.lastPreserved = null;
    b.indexVersion = null;
    b.updatedAt = now;
    return effects().updateState(b.build()).thenReply(Done.getInstance());
  }

  public Effect<Done> markAiTagged(Instant now) {
    Records.Link link = currentState();
    if (link == null || link.deleted()) return effects().error("Link not found.");
    Records.Link.Builder b = link.copy();
    b.aiTagged = true;
    b.updatedAt = now;
    return effects().updateState(b.build()).thenReply(Done.getInstance());
  }

  public Effect<Done> delete(Instant now) {
    Records.Link link = currentState();
    if (link == null || link.deleted()) return effects().reply(Done.getInstance());
    Records.Link.Builder b = link.copy();
    b.deleted = true;
    b.updatedAt = now;
    return effects().updateState(b.build()).thenReply(Done.getInstance());
  }

  /** Whether this link is waiting for the index, at the version the instance is running. */
  public ReadOnlyEffect<Boolean> awaitingIndexing() {
    Records.Link link = currentState();
    if (link == null || link.deleted()) return effects().reply(false);
    return effects()
        .reply(
            link.indexVersion() == null
                || link.indexVersion() != Eligibility.CURRENT_INDEX_VERSION);
  }
}

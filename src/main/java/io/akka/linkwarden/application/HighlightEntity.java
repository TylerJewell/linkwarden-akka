package io.akka.linkwarden.application;

import akka.Done;
import akka.javasdk.annotations.Component;
import akka.javasdk.keyvalueentity.KeyValueEntity;
import io.akka.linkwarden.domain.Records;
import java.time.Instant;

/** One marked passage of a link. SPEC-001 R70. */
@Component(id = "highlight")
public class HighlightEntity extends KeyValueEntity<Records.Highlight> {

  public record Create(
      int id,
      int linkId,
      int userId,
      String color,
      String comment,
      int startOffset,
      int endOffset,
      String text,
      Instant now) {}

  /** SPEC-001 R70 — a second mark of the same range changes only these two. */
  public record Recolour(String color, String comment, Instant now) {}

  public Effect<Records.Highlight> create(Create cmd) {
    Records.Highlight highlight =
        new Records.Highlight(
            cmd.id(), cmd.linkId(), cmd.userId(), cmd.color(), cmd.comment(), cmd.startOffset(),
            cmd.endOffset(), cmd.text(), cmd.now(), cmd.now(), false);
    return effects().updateState(highlight).thenReply(highlight);
  }

  public ReadOnlyEffect<Records.Highlight> get() {
    Records.Highlight highlight = currentState();
    if (highlight == null || highlight.deleted()) return effects().error("Highlight not found.");
    return effects().reply(highlight);
  }

  public Effect<Records.Highlight> recolour(Recolour cmd) {
    Records.Highlight highlight = currentState();
    if (highlight == null || highlight.deleted()) return effects().error("Highlight not found.");
    Records.Highlight updated =
        new Records.Highlight(
            highlight.id(), highlight.linkId(), highlight.userId(), cmd.color(), cmd.comment(),
            highlight.startOffset(), highlight.endOffset(), highlight.text(),
            highlight.createdAt(), cmd.now(), false);
    return effects().updateState(updated).thenReply(updated);
  }

  public Effect<Done> delete(Instant now) {
    Records.Highlight highlight = currentState();
    if (highlight == null || highlight.deleted()) return effects().reply(Done.getInstance());
    Records.Highlight removed =
        new Records.Highlight(
            highlight.id(), highlight.linkId(), highlight.userId(), highlight.color(),
            highlight.comment(), highlight.startOffset(), highlight.endOffset(), highlight.text(),
            highlight.createdAt(), now, true);
    return effects().updateState(removed).thenReply(Done.getInstance());
  }
}

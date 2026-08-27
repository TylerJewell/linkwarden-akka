package io.akka.linkwarden.application;

import akka.Done;
import akka.javasdk.annotations.Component;
import akka.javasdk.keyvalueentity.KeyValueEntity;
import io.akka.linkwarden.domain.Records;
import java.time.Instant;

/** One tag, owned by one account. SPEC-001 R40, R66–R69. */
@Component(id = "tag")
public class TagEntity extends KeyValueEntity<Records.Tag> {

  public record Create(int id, String name, int ownerId, boolean aiGenerated, Instant now) {}

  public record Rename(String name, Instant now) {}

  /** The six archival fields, each nullable — an absent one is written as absent, not skipped. */
  public record SetArchivalFields(
      Boolean archiveAsScreenshot,
      Boolean archiveAsMonolith,
      Boolean archiveAsPDF,
      Boolean archiveAsReadable,
      Boolean archiveAsWaybackMachine,
      Boolean aiTag,
      Instant now) {}

  public Effect<Done> create(Create cmd) {
    if (currentState() != null && !currentState().deleted()) {
      return effects().error("Tag names should be unique.");
    }
    return effects()
        .updateState(
            Records.Tag.fresh(cmd.id(), cmd.name(), cmd.ownerId(), cmd.aiGenerated(), cmd.now()))
        .thenReply(Done.getInstance());
  }

  public ReadOnlyEffect<Records.Tag> get() {
    Records.Tag tag = currentState();
    if (tag == null || tag.deleted()) return effects().error("Tag not found.");
    return effects().reply(tag);
  }

  public Effect<Records.Tag> rename(Rename cmd) {
    Records.Tag tag = currentState();
    if (tag == null || tag.deleted()) return effects().error("Tag not found.");
    Records.Tag.Builder b = tag.copy();
    b.name = cmd.name();
    b.updatedAt = cmd.now();
    Records.Tag updated = b.build();
    return effects().updateState(updated).thenReply(updated);
  }

  public Effect<Records.Tag> setArchivalFields(SetArchivalFields cmd) {
    Records.Tag tag = currentState();
    if (tag == null || tag.deleted()) return effects().error("Tag not found.");
    Records.Tag.Builder b = tag.copy();
    b.archiveAsScreenshot = cmd.archiveAsScreenshot();
    b.archiveAsMonolith = cmd.archiveAsMonolith();
    b.archiveAsPDF = cmd.archiveAsPDF();
    b.archiveAsReadable = cmd.archiveAsReadable();
    b.archiveAsWaybackMachine = cmd.archiveAsWaybackMachine();
    b.aiTag = cmd.aiTag();
    b.updatedAt = cmd.now();
    Records.Tag updated = b.build();
    return effects().updateState(updated).thenReply(updated);
  }

  public Effect<Done> delete(Instant now) {
    Records.Tag tag = currentState();
    if (tag == null || tag.deleted()) return effects().reply(Done.getInstance());
    Records.Tag.Builder b = tag.copy();
    b.deleted = true;
    b.updatedAt = now;
    return effects().updateState(b.build()).thenReply(Done.getInstance());
  }
}

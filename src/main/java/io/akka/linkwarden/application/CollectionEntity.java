package io.akka.linkwarden.application;

import akka.Done;
import akka.javasdk.annotations.Component;
import akka.javasdk.keyvalueentity.KeyValueEntity;
import io.akka.linkwarden.domain.Permissions;
import io.akka.linkwarden.domain.Records;
import java.time.Instant;
import java.util.List;

/** One collection, with its members held on it. SPEC-001 R29–R32. */
@Component(id = "collection")
public class CollectionEntity extends KeyValueEntity<Records.Collection> {

  public record Create(
      int id,
      String name,
      String description,
      String icon,
      String iconWeight,
      String color,
      Integer parentId,
      int ownerId,
      Integer createdById,
      List<Permissions.Member> members,
      Instant now) {}

  public record Update(
      String name,
      String description,
      String icon,
      String iconWeight,
      String color,
      Boolean isPublic,
      /** {@code null} leaves the parent alone; {@code -1} detaches it. */
      Integer parentId,
      List<Permissions.Member> members,
      Instant now) {}

  public record SetMembers(List<Permissions.Member> members, Instant now) {}

  /** The parent value that means "no parent", so an absent field can still mean "leave it". */
  public static final int DETACH = -1;

  public Effect<Done> create(Create cmd) {
    if (currentState() != null && !currentState().deleted()) {
      return effects().error("Collection already exists.");
    }
    return effects()
        .updateState(
            Records.Collection.fresh(
                cmd.id(), cmd.name(), cmd.description(), cmd.icon(), cmd.iconWeight(),
                cmd.color(), cmd.parentId(), cmd.ownerId(), cmd.createdById(), cmd.members(),
                cmd.now()))
        .thenReply(Done.getInstance());
  }

  public ReadOnlyEffect<Records.Collection> get() {
    Records.Collection collection = currentState();
    if (collection == null || collection.deleted()) return effects().error("Collection not found.");
    return effects().reply(collection);
  }

  public Effect<Records.Collection> update(Update cmd) {
    Records.Collection collection = currentState();
    if (collection == null || collection.deleted()) return effects().error("Collection not found.");

    Records.Collection.Builder b = collection.copy();
    if (cmd.name() != null) b.name = cmd.name();
    // An absent description clears it, because the schema makes it optional with an empty
    // default and the original writes whatever the body carried.
    b.description = cmd.description() == null ? "" : cmd.description();
    b.icon = cmd.icon();
    b.iconWeight = cmd.iconWeight();
    if (cmd.color() != null) b.color = cmd.color();
    if (cmd.isPublic() != null) b.isPublic = cmd.isPublic();
    if (cmd.parentId() != null) b.parentId = cmd.parentId() == DETACH ? null : cmd.parentId();
    if (cmd.members() != null) {
      b.members = Permissions.uniqueMembers(cmd.members(), collection.ownerId());
    }
    b.updatedAt = cmd.now();

    Records.Collection updated = b.build();
    return effects().updateState(updated).thenReply(updated);
  }

  public Effect<Done> setMembers(SetMembers cmd) {
    Records.Collection collection = currentState();
    if (collection == null || collection.deleted()) return effects().error("Collection not found.");
    Records.Collection.Builder b = collection.copy();
    b.members = Permissions.uniqueMembers(cmd.members(), collection.ownerId());
    b.updatedAt = cmd.now();
    return effects().updateState(b.build()).thenReply(Done.getInstance());
  }

  /** Drops one member's row, which is what leaving a collection does. SPEC-001 R32. */
  public Effect<Done> removeMember(int userId) {
    Records.Collection collection = currentState();
    if (collection == null || collection.deleted()) return effects().error("Collection not found.");
    Records.Collection.Builder b = collection.copy();
    b.members = collection.members().stream().filter(m -> m.userId() != userId).toList();
    return effects().updateState(b.build()).thenReply(Done.getInstance());
  }

  public Effect<Done> delete(Instant now) {
    Records.Collection collection = currentState();
    if (collection == null || collection.deleted()) return effects().reply(Done.getInstance());
    Records.Collection.Builder b = collection.copy();
    b.deleted = true;
    b.members = List.of();
    b.updatedAt = now;
    return effects().updateState(b.build()).thenReply(Done.getInstance());
  }
}

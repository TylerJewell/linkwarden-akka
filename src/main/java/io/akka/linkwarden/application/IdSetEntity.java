package io.akka.linkwarden.application;

import akka.Done;
import akka.javasdk.annotations.Component;
import akka.javasdk.keyvalueentity.KeyValueEntity;
import java.util.ArrayList;
import java.util.List;

/**
 * A set of identifiers under one key, for the relations a rule walks rather than lists.
 *
 * <p>Three of Linkwarden's rules walk structure and must see the write that has just happened:
 * deleting a collection reaches every descendant, propagating members reaches every descendant,
 * and deleting a collection removes every link in it. A projection cannot serve those — it is
 * behind by however long its stream is, and a child created a moment before the delete would be
 * left behind with no parent. So the parent-child, owner-collection and collection-link relations
 * are kept here, written by whoever changes them, and read directly.
 *
 * <p>The views alongside are still what the listing routes read: a list that is a fraction of a
 * second behind is a projection working, and a cascade that is behind is a defect.
 */
@Component(id = "id-set")
public class IdSetEntity extends KeyValueEntity<IdSetEntity.Members> {

  public record Members(List<Integer> ids) {}

  @Override
  public Members emptyState() {
    return new Members(List.of());
  }

  public ReadOnlyEffect<Members> get() {
    return effects().reply(currentState());
  }

  public Effect<Done> add(Integer id) {
    if (currentState().ids().contains(id)) return effects().reply(Done.getInstance());
    List<Integer> ids = new ArrayList<>(currentState().ids());
    ids.add(id);
    return effects().updateState(new Members(List.copyOf(ids))).thenReply(Done.getInstance());
  }

  public Effect<Done> remove(Integer id) {
    if (!currentState().ids().contains(id)) return effects().reply(Done.getInstance());
    List<Integer> ids = new ArrayList<>(currentState().ids());
    ids.remove(id);
    return effects().updateState(new Members(List.copyOf(ids))).thenReply(Done.getInstance());
  }
}

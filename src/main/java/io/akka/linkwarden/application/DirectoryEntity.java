package io.akka.linkwarden.application;

import akka.Done;
import akka.javasdk.annotations.Component;
import akka.javasdk.keyvalueentity.KeyValueEntity;

/**
 * Which account holds one username or one address. SPEC-001 R12, R16.
 *
 * <p>Both questions the sign-in and the registration rules ask are lookups by a value that is
 * unique, and both must answer about the write that has just happened: a caller registers and
 * signs in on the next request, and a second registration of the same name must be refused by the
 * first. A projection answers neither — it is behind by however long its stream is — so the two
 * unique values are keys of their own here, claimed as part of registering.
 *
 * <p>{@code claim} refuses a name already held, which is what makes the refusal a fact rather than
 * a race: two registrations of one name reach this entity in some order, and the second is told.
 */
@Component(id = "directory")
public class DirectoryEntity extends KeyValueEntity<DirectoryEntity.Held> {

  /** @param userId the account holding this value, or 0 when nobody does */
  public record Held(int userId) {}

  @Override
  public Held emptyState() {
    return new Held(0);
  }

  public ReadOnlyEffect<Held> get() {
    return effects().reply(currentState());
  }

  public Effect<Boolean> claim(Integer userId) {
    if (currentState().userId() != 0 && currentState().userId() != userId) {
      return effects().reply(false);
    }
    return effects().updateState(new Held(userId)).thenReply(true);
  }

  public Effect<Done> release() {
    return effects().updateState(new Held(0)).thenReply(Done.getInstance());
  }
}

package io.akka.linkwarden.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.keyvalueentity.KeyValueEntity;

/**
 * The ascending integer keys every record is identified by. SPEC-001 §2.1 and open decision D.
 *
 * <p>Linkwarden's ordering is its database's sequence: {@code id desc} is "newest first} everywhere
 * a list is sorted, and no rule reads a timestamp to order two records. One counter per kind
 * reproduces that, so a rule about ordering keeps its meaning rather than being approximated by a
 * clock two nodes need not agree on.
 */
@Component(id = "counter")
public class CounterEntity extends KeyValueEntity<CounterEntity.Counter> {

  public record Counter(int next) {}

  @Override
  public Counter emptyState() {
    return new Counter(1);
  }

  /** Hands out the next key and moves on. */
  public Effect<Integer> take() {
    int assigned = currentState().next();
    return effects().updateState(new Counter(assigned + 1)).thenReply(assigned);
  }

  public ReadOnlyEffect<Integer> peek() {
    return effects().reply(currentState().next());
  }
}

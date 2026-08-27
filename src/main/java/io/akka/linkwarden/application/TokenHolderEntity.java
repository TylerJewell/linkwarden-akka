package io.akka.linkwarden.application;

import akka.Done;
import akka.javasdk.annotations.Component;
import akka.javasdk.keyvalueentity.KeyValueEntity;

/**
 * Which store a presented token belongs to. SPEC-001 R19, R22.
 *
 * <p>Both routes that start from a token and have to find the address take only the token, so the
 * lookup has to go the other way round. Keyed by the token's stored form, holding the store's own
 * key — which means neither the address nor anything presentable is in the key.
 */
@Component(id = "token-holder")
public class TokenHolderEntity extends KeyValueEntity<TokenHolderEntity.Holder> {

  public record Holder(String storeKey) {}

  @Override
  public Holder emptyState() {
    return new Holder("");
  }

  public ReadOnlyEffect<Holder> get() {
    return effects().reply(currentState());
  }

  public Effect<Done> point(String storeKey) {
    return effects().updateState(new Holder(storeKey)).thenReply(Done.getInstance());
  }
}

package io.akka.linkwarden.application;

import akka.Done;
import akka.javasdk.annotations.Component;
import akka.javasdk.keyvalueentity.KeyValueEntity;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The verification and password-reset tokens outstanding for one address. SPEC-001 R18–R22.
 *
 * <p>Keyed by the address rather than by the token, because every rule that reads these is about
 * an address: how many were asked for in the last five minutes, and which of them are still live.
 * Keying by the token would make each of those a scan.
 *
 * <p>What is stored is never the token. For verification it is {@code sha256(token ‖ secret)}, and
 * a reader holding the store still cannot present anything with it.
 */
@Component(id = "issued-tokens")
public class IssuedTokensEntity extends KeyValueEntity<IssuedTokensEntity.Store> {

  /** One outstanding token. {@code stored} is whatever the rule that minted it decided to keep. */
  public record Issued(String stored, Instant expires, Instant createdAt) {}

  /**
   * @param key the entity's own key, which says which of the two kinds this store is
   * @param identifier the address every token in it was issued to
   */
  public record Store(String key, String identifier, List<Issued> tokens) {}

  /**
   * @param key this store's own key, carried on the command because a key-value entity's empty
   *     state is built without a command context to read it from
   */
  public record Issue(String key, String identifier, String stored, Instant expires, Instant now) {}

  /** Expiring a token by setting its expiry to now, which is what a spent reset token gets. */
  public record Spend(String stored, Instant now) {}

  @Override
  public Store emptyState() {
    return new Store("", "", List.of());
  }

  public ReadOnlyEffect<Store> get() {
    return effects().reply(currentState());
  }

  public Effect<Done> issue(Issue cmd) {
    List<Issued> tokens = new ArrayList<>(currentState().tokens());
    tokens.add(new Issued(cmd.stored(), cmd.expires(), cmd.now()));
    return effects()
        .updateState(new Store(cmd.key(), cmd.identifier(), List.copyOf(tokens)))
        .thenReply(Done.getInstance());
  }

  /**
   * SPEC-001 R19 — the token used is expired where it stands, and every token older than five
   * minutes is dropped. The two are one command because the rule performs them together: a spent
   * token that is not also swept would be dropped on the next request and stop refusing reuse.
   */
  public Effect<Done> spend(Spend cmd) {
    Instant cutoff = cmd.now().minusSeconds(300);
    List<Issued> kept = new ArrayList<>();
    for (Issued token : currentState().tokens()) {
      if (token.createdAt().isBefore(cutoff)) continue;
      kept.add(
          token.stored().equals(cmd.stored())
              ? new Issued(token.stored(), cmd.now(), token.createdAt())
              : token);
    }
    return effects()
        .updateState(new Store(currentState().key(), currentState().identifier(), List.copyOf(kept)))
        .thenReply(Done.getInstance());
  }

  /** SPEC-001 R21 — a verified address keeps none of its verification tokens. */
  public Effect<Done> clear() {
    return effects()
        .updateState(new Store(currentState().key(), currentState().identifier(), List.of()))
        .thenReply(Done.getInstance());
  }
}

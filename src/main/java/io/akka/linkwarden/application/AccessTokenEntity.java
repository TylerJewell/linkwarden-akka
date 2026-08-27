package io.akka.linkwarden.application;

import akka.Done;
import akka.javasdk.annotations.Component;
import akka.javasdk.keyvalueentity.KeyValueEntity;
import io.akka.linkwarden.domain.Records;
import java.time.Instant;

/**
 * One API token or one session. SPEC-001 R13–R14.
 *
 * <p>What is stored is the token's identifier and never the token: the token itself is disclosed
 * once, at the moment it is minted, and revoking marks the row rather than deleting it so a token
 * already in somebody's hands can be recognised as revoked.
 */
@Component(id = "access-token")
public class AccessTokenEntity extends KeyValueEntity<Records.AccessToken> {

  public record Create(
      int id, String name, int userId, String jti, boolean isSession, Instant expires,
      Instant now) {}

  public Effect<Done> create(Create cmd) {
    return effects()
        .updateState(
            new Records.AccessToken(
                cmd.id(), cmd.name(), cmd.userId(), cmd.jti(), false, cmd.isSession(),
                cmd.expires(), null, cmd.now(), cmd.now()))
        .thenReply(Done.getInstance());
  }

  public ReadOnlyEffect<Records.AccessToken> get() {
    Records.AccessToken token = currentState();
    if (token == null) return effects().error("Token not found.");
    return effects().reply(token);
  }

  public Effect<Records.AccessToken> revoke(Instant now) {
    Records.AccessToken token = currentState();
    if (token == null) return effects().error("Token not found.");
    Records.AccessToken revoked =
        new Records.AccessToken(
            token.id(), token.name(), token.userId(), token.jti(), true, token.isSession(),
            token.expires(), token.lastUsedAt(), token.createdAt(), now);
    return effects().updateState(revoked).thenReply(revoked);
  }

  public Effect<Done> markUsed(Instant now) {
    Records.AccessToken token = currentState();
    if (token == null) return effects().error("Token not found.");
    Records.AccessToken used =
        new Records.AccessToken(
            token.id(), token.name(), token.userId(), token.jti(), token.revoked(),
            token.isSession(), token.expires(), now, token.createdAt(), now);
    return effects().updateState(used).thenReply(Done.getInstance());
  }
}

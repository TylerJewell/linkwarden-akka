package io.akka.linkwarden.application;

import io.akka.linkwarden.domain.Config;
import io.akka.linkwarden.domain.Ids;
import io.akka.linkwarden.domain.Tokens;
import java.time.Instant;
import java.util.UUID;

/**
 * Minting a token and recording that it exists. SPEC-001 R12–R13.
 *
 * <p>Two routes hand out a session — signing in, and confirming an address — and both must record
 * the same row, because revoking a session is a write to that row rather than to the token. What
 * is stored is the token's identifier, never the token.
 */
public final class Sessions {

  private Sessions() {}

  public static String mintAndRecord(
      Data data,
      Config config,
      int userId,
      String name,
      boolean isSession,
      Instant expires,
      Instant now) {
    String jti = UUID.randomUUID().toString();
    String token = Tokens.mint(config.raw("NEXTAUTH_SECRET"), userId, jti, now, expires, null);
    int id = data.nextId("access-token");
    data.client()
        .forKeyValueEntity(Ids.accessToken(id))
        .method(AccessTokenEntity::create)
        .invoke(new AccessTokenEntity.Create(id, name, userId, jti, isSession, expires, now));
    data.accessToken(id).ifPresent(data::indexToken);
    return token;
  }
}

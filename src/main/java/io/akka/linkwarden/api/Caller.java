package io.akka.linkwarden.api;

import akka.http.javadsl.model.HttpResponse;
import io.akka.linkwarden.application.Data;
import io.akka.linkwarden.domain.Config;
import io.akka.linkwarden.domain.Records;
import io.akka.linkwarden.domain.Tokens;
import java.time.Instant;
import java.util.Optional;

/**
 * Who is asking. SPEC-001 R9–R11.
 *
 * <p>Two checks, not one: the thinner one asks only whether the token is usable, and the fuller
 * one goes on to ask whether the account it names is in a state that may use the surface at all.
 * Routes differ in which they apply — the archive reader takes the thin one so a public
 * collection can be read by somebody signed in as nobody in particular — and collapsing them into
 * one would change who can read what.
 */
public final class Caller {

  /** The outcome of a check: either an account, or the answer to send instead. */
  public record Result(Records.User user, HttpResponse refusal) {

    public boolean refused() {
      return refusal != null;
    }

    static Result of(Records.User user) {
      return new Result(user, null);
    }

    static Result refusedWith(int status, String message) {
      return new Result(null, Answers.wrapped(status, message));
    }
  }

  private final Data data;
  private final Config config;

  public Caller(Data data, Config config) {
    this.data = data;
    this.config = config;
  }

  /** The bearer token on the request, if there is one. SPEC-001 R9. */
  public static Optional<String> bearer(String authorization) {
    if (authorization == null) return Optional.empty();
    String trimmed = authorization.trim();
    if (!trimmed.toLowerCase().startsWith("bearer ")) return Optional.empty();
    return Optional.of(trimmed.substring(7).trim());
  }

  private String secret() {
    String secret = config.raw("NEXTAUTH_SECRET");
    if (secret == null) throw new IllegalStateException("NEXTAUTH_SECRET is not configured.");
    return secret;
  }

  /**
   * SPEC-001 R10 — the token alone: is it ours, is it still inside its lifetime, and has it been
   * revoked.
   */
  public Result fromToken(String authorization, Instant now) {
    Optional<String> presented = bearer(authorization);
    if (presented.isEmpty()) return Result.refusedWith(401, "You must be logged in.");

    Optional<Tokens.Claims> claims = Tokens.read(secret(), presented.get());
    if (claims.isEmpty()) return Result.refusedWith(401, "You must be logged in.");
    if (claims.get().expires().isBefore(now)) {
      return Result.refusedWith(401, "Your session has expired, please log in again.");
    }
    Optional<Records.AccessToken> row = data.tokenWithIdentifier(claims.get().jti());
    if (row.isPresent() && row.get().revoked()) {
      return Result.refusedWith(401, "Your session has expired, please log in again.");
    }
    Optional<Records.User> user = data.user(claims.get().userId());
    if (user.isEmpty()) return Result.refusedWith(404, "User not found.");
    return Result.of(user.get());
  }

  /** SPEC-001 R11 — everything above, and then whether this account may use the surface. */
  public Result fromRequest(String authorization, Instant now) {
    Result token = fromToken(authorization, now);
    if (token.refused()) return token;

    Records.User user = token.user();
    if (user.username() == null || user.username().isEmpty()) {
      return Result.refusedWith(401, "Username not found.");
    }
    if (user.emailVerified() == null && config.flag("NEXT_PUBLIC_EMAIL_PROVIDER")) {
      return Result.refusedWith(
          401,
          "Email not verified, please verify your email to continue using Linkwarden.");
    }
    if (config.billingEnabled()) {
      return Result.refusedWith(
          401,
          "You are not a subscriber, feel free to reach out to us at support@linkwarden.app if"
              + " you think this is an issue.");
    }
    return Result.of(user);
  }

  /** The account behind a token when there is one, and nobody when there is not. */
  public Optional<Records.User> optional(String authorization, Instant now) {
    Result result = fromToken(authorization, now);
    return result.refused() ? Optional.empty() : Optional.of(result.user());
  }

  public boolean isAdministrator(Records.User user) {
    return user.id() == config.adminId();
  }
}

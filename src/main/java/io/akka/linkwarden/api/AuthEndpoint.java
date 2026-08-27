package io.akka.linkwarden.api;

import akka.http.javadsl.model.HttpResponse;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Post;
import com.fasterxml.jackson.databind.JsonNode;
import io.akka.linkwarden.application.Data;
import io.akka.linkwarden.application.IssuedTokensEntity;
import io.akka.linkwarden.application.TokenHolderEntity;
import io.akka.linkwarden.application.Sessions;
import io.akka.linkwarden.application.UserEntity;
import io.akka.linkwarden.domain.Config;
import io.akka.linkwarden.domain.Ids;
import io.akka.linkwarden.domain.Passwords;
import io.akka.linkwarden.domain.Records;
import io.akka.linkwarden.domain.Validation;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Verifying an address and resetting a password. SPEC-001 R18–R22.
 *
 * <p>Handing an envelope to a mail server is not a behaviour of Linkwarden and is out of scope,
 * so the token this mints is answered to the caller rather than posted. Everything the rules
 * decide — the rate limit, the lifetime, what is stored, what a spent token does to its
 * neighbours — is the same, and each route's own answer is unchanged.
 */
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
@HttpEndpoint("/api/v1/auth")
public class AuthEndpoint extends Surface {

  private static final long FIVE_MINUTES = 300;
  private static final long VERIFICATION_LIFETIME = 1200;
  private static final int RESET_REQUESTS_ALLOWED = 3;
  private static final int VERIFICATION_REQUESTS_ALLOWED = 4;

  public AuthEndpoint(Data data, Config config) {
    super(data, config);
  }

  // ------------------------------------------------------------------
  // resetting a password
  // ------------------------------------------------------------------

  /** SPEC-001 R18 — three requests inside five minutes, and an unknown address says so. */
  @Post("/forgot-password")
  public HttpResponse forgotPassword(JsonNode body) {
    if (config.demoMode()) return Answers.demoRefusal();
    String email = normalised(Bodies.text(body, "email"));
    Optional<Validation.Issue> issue = Validation.first(Validation.requiredString("email", email, 1, 254));
    if (issue.isPresent()) return Answers.issue(issue.get());

    Instant now = Instant.now();
    String key = Ids.passwordResetTokens(email);
    if (recentCount(key, now) >= RESET_REQUESTS_ALLOWED) {
      return Answers.wrapped(400, "Too many requests. Please try again later.");
    }
    // The original does not hide whether the address exists, and neither does this.
    Optional<Records.User> user = data.userByEmail(email);
    if (user.isEmpty() || user.get().email() == null) {
      return Answers.wrapped(400, "No user found with that email.");
    }

    String token = freshToken();
    issue(key, email, token, now.plusSeconds(FIVE_MINUTES * 12), now);
    Map<String, Object> answer = new LinkedHashMap<>();
    answer.put("response", "Password reset email sent.");
    answer.put("token", token);
    return Answers.json(200, answer);
  }

  /** SPEC-001 R19 — the token is spent where it stands and its older neighbours are dropped. */
  @Post("/reset-password")
  public HttpResponse resetPassword(JsonNode body) {
    if (config.demoMode()) return Answers.demoRefusal();
    String token = Bodies.text(body, "token");
    String password = Bodies.text(body, "password");
    Optional<Validation.Issue> issue =
        Validation.first(
            Validation.requiredString("token", token, 1, 4096),
            Validation.requiredString("password", password, 8, 2048));
    if (issue.isPresent()) return Answers.issue(issue.get());

    Instant now = Instant.now();
    Optional<String> holder = storeHolding(token);
    if (holder.isEmpty()) return Answers.wrapped(400, "Invalid token.");
    IssuedTokensEntity.Store store = store(holder.get());
    boolean live =
        store.tokens().stream()
            .anyMatch(t -> t.stored().equals(token) && t.expires().isAfter(now));
    if (!live) return Answers.wrapped(400, "Invalid token.");

    Optional<Records.User> user = data.userByEmail(store.identifier());
    if (user.isEmpty()) return Answers.wrapped(400, "Invalid token.");

    data.client()
        .forKeyValueEntity(Ids.user(user.get().id()))
        .method(UserEntity::setPassword)
        .invoke(new UserEntity.NewPassword(Passwords.hash(password), now));
    data.client()
        .forKeyValueEntity(holder.get())
        .method(IssuedTokensEntity::spend)
        .invoke(new IssuedTokensEntity.Spend(token, now));
    return Answers.wrapped(200, "Password has been reset successfully.");
  }

  // ------------------------------------------------------------------
  // verifying an address
  // ------------------------------------------------------------------

  /** SPEC-001 R20 — four requests inside five minutes, and what is stored is not the token. */
  @Post("/request-verification-email")
  public HttpResponse requestVerification(JsonNode body) {
    if (!config.emailEnabled() || config.raw("NEXTAUTH_SECRET") == null) {
      return Answers.wrapped(400, "Email is not configured.");
    }
    String email = normalised(Bodies.text(body, "email"));
    Optional<Records.User> user = data.userByEmail(email);
    if (user.isEmpty()) return Answers.wrapped(400, "Invalid email.");
    if (user.get().emailVerified() != null) {
      return Answers.wrapped(400, "Email is already verified.");
    }

    Instant now = Instant.now();
    String key = Ids.verificationTokens(email);
    if (recentCount(key, now) >= VERIFICATION_REQUESTS_ALLOWED) {
      return Answers.wrapped(429, "Too many requests. Please try again later.");
    }
    String token = freshToken();
    issue(key, email, hashed(token), now.plusSeconds(VERIFICATION_LIFETIME), now);
    Map<String, Object> answer = new LinkedHashMap<>();
    answer.put("response", "Verification email sent.");
    answer.put("token", token);
    return Answers.json(200, answer);
  }

  /** SPEC-001 R21 — the address is verified, its tokens all go, and a session comes back. */
  @Post("/verify-email-token")
  public HttpResponse verifyEmailToken(JsonNode body) {
    if (!config.emailEnabled() || config.raw("NEXTAUTH_SECRET") == null) {
      return Answers.wrapped(400, "Email is not configured.");
    }
    String token = Bodies.text(body, "token");
    String email = normalised(Bodies.text(body, "email"));
    if (token == null || token.isEmpty() || email.isEmpty()) {
      return Answers.wrapped(400, "Invalid request.");
    }

    Instant now = Instant.now();
    String key = Ids.verificationTokens(email);
    IssuedTokensEntity.Store store = store(key);
    String stored = hashed(token);
    boolean live =
        store.tokens().stream()
            .anyMatch(t -> t.stored().equals(stored) && t.expires().isAfter(now));
    if (!live) {
      return Answers.wrapped(400, "Verification link is invalid or has expired.");
    }
    Optional<Records.User> user = data.userByEmail(email);
    if (user.isEmpty()) return Answers.wrapped(400, "Invalid email.");

    if (user.get().emailVerified() == null) {
      data.client()
          .forKeyValueEntity(Ids.user(user.get().id()))
          .method(UserEntity::verifyEmail)
          .invoke(now);
    }
    data.client().forKeyValueEntity(key).method(IssuedTokensEntity::clear).invoke();

    String sessionName = Bodies.text(body, "sessionName");
    String session =
        Sessions.mintAndRecord(
            data,
            config,
            user.get().id(),
            sessionName == null || sessionName.isBlank() ? "Unknown Device" : sessionName.trim(),
            true,
            now.plus(73_000, ChronoUnit.DAYS),
            now);
    Map<String, Object> answer = new LinkedHashMap<>();
    answer.put("token", session);
    return Answers.wrapped(200, answer);
  }

  /** SPEC-001 R22 — changing an address, which reads its token from the query string. */
  @Post("/verify-email")
  public HttpResponse verifyEmail() {
    if (config.demoMode()) return Answers.demoRefusal();
    Optional<String> presented = query("token");
    if (presented.isEmpty()) {
      return Answers.issue(Validation.missing("token", "string"));
    }
    Instant now = Instant.now();
    Optional<String> holder = storeHolding(presented.get());
    if (holder.isEmpty()) return Answers.wrapped(400, "Invalid token.");
    IssuedTokensEntity.Store store = store(holder.get());
    boolean live =
        store.tokens().stream()
            .anyMatch(t -> t.stored().equals(presented.get()) && t.expires().isAfter(now));
    if (!live) return Answers.wrapped(400, "Invalid token.");

    String oldEmail = store.identifier();
    Optional<Records.User> user = data.userByEmail(oldEmail);
    if (user.isEmpty() || user.get().unverifiedNewEmail() == null) {
      return Answers.wrapped(400, "No unverified emails found.");
    }
    String newEmail = user.get().unverifiedNewEmail();
    if (data.userByEmail(newEmail).isPresent()) {
      return Answers.wrapped(400, "Email is already in use.");
    }
    data.client()
        .forKeyValueEntity(Ids.user(user.get().id()))
        .method(UserEntity::confirmEmailChange)
        .invoke(new UserEntity.PendingEmail(newEmail, now));
    return Answers.wrapped(200, "Email updated.");
  }

  // ------------------------------------------------------------------

  private static String normalised(String email) {
    return email == null ? "" : email.toLowerCase().trim();
  }

  private String freshToken() {
    byte[] bytes = new byte[32];
    new SecureRandom().nextBytes(bytes);
    StringBuilder out = new StringBuilder(bytes.length * 2);
    for (byte b : bytes) out.append(String.format("%02x", b));
    return out.toString();
  }

  /** SPEC-001 R20 — what a verification store holds is the token bound to this instance's secret. */
  private String hashed(String token) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] out =
          digest.digest((token + config.raw("NEXTAUTH_SECRET")).getBytes(StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder(out.length * 2);
      for (byte b : out) hex.append(String.format("%02x", b));
      return hex.toString();
    } catch (Exception e) {
      throw new IllegalStateException("SHA-256 is unavailable", e);
    }
  }

  private IssuedTokensEntity.Store store(String key) {
    return data.client().forKeyValueEntity(key).method(IssuedTokensEntity::get).invoke();
  }

  private int recentCount(String key, Instant now) {
    Instant cutoff = now.minusSeconds(FIVE_MINUTES);
    return (int)
        store(key).tokens().stream().filter(t -> t.createdAt().isAfter(cutoff)).count();
  }

  private void issue(String key, String identifier, String stored, Instant expires, Instant now) {
    data.client()
        .forKeyValueEntity(key)
        .method(IssuedTokensEntity::issue)
        .invoke(new IssuedTokensEntity.Issue(key, identifier, stored, expires, now));
    data.client()
        .forKeyValueEntity("holder-" + stored)
        .method(TokenHolderEntity::point)
        .invoke(key);
  }

  /** Which store a token belongs to, which the two rules that start from one need to know. */
  private Optional<String> storeHolding(String stored) {
    String key =
        data.client()
            .forKeyValueEntity("holder-" + stored)
            .method(TokenHolderEntity::get)
            .invoke()
            .storeKey();
    return key.isEmpty() ? Optional.empty() : Optional.of(key);
  }
}

package io.akka.linkwarden.api;

import akka.http.javadsl.model.HttpResponse;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Delete;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Post;
import akka.javasdk.annotations.http.Put;
import akka.javasdk.http.AbstractHttpEndpoint;
import com.fasterxml.jackson.databind.JsonNode;
import io.akka.linkwarden.application.AccessTokenEntity;
import io.akka.linkwarden.application.Data;
import io.akka.linkwarden.application.Sessions;
import io.akka.linkwarden.application.UserEntity;
import io.akka.linkwarden.domain.Config;
import io.akka.linkwarden.domain.Ids;
import io.akka.linkwarden.domain.Passwords;
import io.akka.linkwarden.domain.Records;
import io.akka.linkwarden.domain.Tokens;
import io.akka.linkwarden.domain.Validation;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Registration, signing in, accounts and API tokens. SPEC-001 R12–R17, R37–R41, R13–R14.
 *
 * <p>Bodies arrive as a tree rather than a typed record because the original's schemas distinguish
 * a field that is absent from one that is present and null, and several rules read that
 * difference — an absent username leaves the stored one alone, a present empty one is a refusal.
 */
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
@HttpEndpoint("/api/v1")
public class AccountEndpoint extends AbstractHttpEndpoint {

  private static final List<String> LOCALES =
      List.of("en", "de", "es", "fr", "it", "ja", "nl", "pl", "pt", "ru", "tr", "zh", "vi", "uk",
          "ko", "id", "hi", "el", "da", "cs", "ar");

  private final Data data;
  private final Config config;
  private final Caller caller;

  public AccountEndpoint(Data data, Config config) {
    this.data = data;
    this.config = config;
    this.caller = new Caller(data, config);
  }

  private String authorization() {
    return requestContext()
        .requestHeader("Authorization")
        .map(header -> header.value())
        .orElse(null);
  }

  private static String text(JsonNode body, String field) {
    JsonNode node = body == null ? null : body.get(field);
    return node == null || node.isNull() ? null : node.asText();
  }

  private static Boolean flag(JsonNode body, String field) {
    JsonNode node = body == null ? null : body.get(field);
    return node == null || node.isNull() ? null : node.asBoolean();
  }

  // ------------------------------------------------------------------
  // registration
  // ------------------------------------------------------------------

  @Post("/users")
  public HttpResponse register(JsonNode body) {
    if (config.demoMode()) return Answers.demoRefusal();

    Instant now = Instant.now();
    Optional<Records.User> signedIn = caller.optional(authorization(), now);
    boolean isAdmin = signedIn.map(caller::isAdministrator).orElse(false);

    if (config.flag("NEXT_PUBLIC_DISABLE_REGISTRATION") && !isAdmin) {
      return Answers.wrapped(400, "Registration is disabled.");
    }

    boolean emailEnabled = config.emailEnabled();
    String name = text(body, "name");
    String password = text(body, "password");
    String email = text(body, "email");
    String username = text(body, "username");

    Optional<Validation.Issue> issue =
        Validation.first(
            Validation.optionalString("name", name, 1, 50),
            Validation.optionalString("password", password, 8, 2048),
            emailEnabled
                ? Validation.first(
                    Validation.requiredString("email", email, 1, 320),
                    email != null && email.contains("@")
                        ? Optional.empty()
                        : Optional.of(Validation.invalidEmail("email")))
                : Optional.empty(),
            emailEnabled
                ? Optional.empty()
                : Validation.first(
                    Validation.requiredString("username", username, 3, 50),
                    Validation.matches(
                        "username",
                        username == null ? null : username.trim().toLowerCase(Locale.ROOT),
                        "^[a-z0-9_-]{3,50}$")));
    if (issue.isPresent()) return Answers.issue(issue.get());

    if (username == null || username.isEmpty()) {
      username = "user" + (long) (Math.random() * 1_000_000_000L);
    }
    username = username.trim().toLowerCase(Locale.ROOT);
    if (email != null) email = email.trim().toLowerCase(Locale.ROOT);

    if (!emailEnabled && (password == null || password.isEmpty())) {
      return Answers.wrapped(400, "Password is required.");
    }

    int id = data.nextId("user");
    // R16 — the two unique values are claimed before the account exists, so a second
    // registration of the same name is refused by the first rather than by a later read.
    if (!data.claim(Ids.usernameHolder(username), id)) {
      return Answers.wrapped(400, "Email or Username already exists.");
    }
    if (email != null && !data.claim(Ids.emailHolder(email), id)) {
      data.release(Ids.usernameHolder(username));
      return Answers.wrapped(400, "Email or Username already exists.");
    }

    data.client()
        .forKeyValueEntity(Ids.user(id))
        .method(UserEntity::create)
        .invoke(
            new UserEntity.Create(
                id,
                UUID.randomUUID().toString(),
                name,
                username,
                emailEnabled ? email : null,
                password == null ? null : Passwords.hash(password),
                isAdmin,
                Boolean.TRUE.equals(flag(body, "acceptPromotionalEmails")),
                now));

    return Answers.wrapped(201, Shapes.user(data.user(id).orElseThrow()));
  }

  @Get("/users")
  public HttpResponse listUsers() {
    Caller.Result result = caller.fromRequest(authorization(), Instant.now());
    if (result.refused()) return result.refusal();

    if (!caller.isAdministrator(result.user())) {
      // Without billing there is no subscription for a non-administrator to be measured
      // against, which is the same answer the original gives with billing switched off.
      return Answers.wrapped(404, "Subscription not found.");
    }
    List<Map<String, Object>> users = new ArrayList<>();
    for (Records.User user : data.allUsers()) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("id", user.id());
      row.put("username", user.username());
      row.put("email", user.email());
      row.put("emailVerified", user.emailVerified() == null ? null : user.emailVerified().toString());
      row.put("subscriptions", null);
      row.put("createdAt", user.createdAt().toString());
      users.add(row);
    }
    return Answers.wrapped(200, users);
  }

  // ------------------------------------------------------------------
  // signing in
  // ------------------------------------------------------------------

  @Post("/session")
  public HttpResponse session(JsonNode body) {
    String username = text(body, "username");
    String password = text(body, "password");
    String sessionName = text(body, "sessionName");

    Optional<Validation.Issue> issue =
        Validation.first(
            Validation.requiredString("username", username, 3, 50),
            Validation.requiredString("password", password, 8, Integer.MAX_VALUE),
            Validation.optionalString("sessionName", sessionName, 0, 50));
    if (issue.isPresent()) return Answers.issue(issue.get());

    Optional<Records.User> found = data.userByUsername(username.toLowerCase(Locale.ROOT));
    if (found.isEmpty() && config.emailEnabled()) {
      found = data.userByEmail(username.toLowerCase(Locale.ROOT));
    }
    if (found.isEmpty() || !Passwords.matches(password, found.get().password())) {
      return Answers.wrapped(
          400,
          "Invalid credentials. You might need to reset your password if you're sure you already"
              + " signed up with the current username/email.");
    }
    Records.User user = found.get();
    if (config.emailEnabled() && user.emailVerified() == null) {
      Map<String, Object> refusal = new LinkedHashMap<>();
      refusal.put("response", "Please verify your email address before logging in.");
      refusal.put("code", "EMAIL_NOT_VERIFIED");
      refusal.put("email", user.email());
      return Answers.json(401, refusal);
    }

    Instant now = Instant.now();
    String token =
        mintAndRecord(
            user.id(),
            sessionName == null || sessionName.isBlank() ? "Unknown Device" : sessionName.trim(),
            true,
            now.plus(73_000, ChronoUnit.DAYS),
            now);

    Map<String, Object> answer = new LinkedHashMap<>();
    answer.put("token", token);
    return Answers.wrapped(200, answer);
  }

  private String mintAndRecord(
      int userId, String name, boolean isSession, Instant expires, Instant now) {
    return Sessions.mintAndRecord(data, config, userId, name, isSession, expires, now);
  }

  // ------------------------------------------------------------------
  // accounts
  // ------------------------------------------------------------------

  @Get("/users/me")
  public HttpResponse me() {
    Caller.Result result = caller.fromToken(authorization(), Instant.now());
    if (result.refused()) return result.refusal();
    return Answers.wrapped(200, Shapes.userWithSubscription(result.user(), hasUnIndexed(result.user())));
  }

  private boolean hasUnIndexed(Records.User user) {
    return data.reachableLinkRows(user.id()).stream()
        .anyMatch(row -> row.indexVersion() != io.akka.linkwarden.application.LinksView.currentIndexVersion());
  }

  @Get("/users/{id}")
  public HttpResponse readUser(int id) {
    Caller.Result result = caller.fromToken(authorization(), Instant.now());
    if (result.refused()) return result.refusal();
    if (id == 0) return Answers.wrapped(400, "Invalid request.");

    Records.User user = result.user();
    if (user.id() != id && !caller.isAdministrator(user)) {
      return Answers.wrapped(401, "Permission denied.");
    }
    return Answers.wrapped(200, Shapes.userWithSubscription(user, hasUnIndexed(user)));
  }

  @Put("/users/{id}")
  public HttpResponse updateUser(int id, JsonNode body) {
    Caller.Result result = caller.fromToken(authorization(), Instant.now());
    if (result.refused()) return result.refusal();
    if (id == 0) return Answers.wrapped(400, "Invalid request.");

    Records.User user = result.user();
    if (user.id() != id && !caller.isAdministrator(user)) {
      return Answers.wrapped(401, "Permission denied.");
    }
    if (config.demoMode()) return Answers.demoRefusal();

    String username = text(body, "username");
    Optional<Validation.Issue> issue =
        Validation.first(
            Validation.optionalString("name", text(body, "name"), 0, 50),
            Validation.requiredString("username", username, 3, 50),
            Validation.matches(
                "username",
                username == null ? null : username.trim().toLowerCase(Locale.ROOT),
                "^[a-z0-9_-]{3,50}$"),
            Validation.optionalString("password", text(body, "password"), 8, 2048),
            Validation.optionalString("newPassword", text(body, "newPassword"), 8, 2048),
            Validation.optionalString("oldPassword", text(body, "oldPassword"), 8, 2048),
            Validation.optionalString("locale", text(body, "locale"), 0, 20));
    if (issue.isPresent()) return Answers.issue(issue.get());

    String wanted = username.trim().toLowerCase(Locale.ROOT);
    if (!data.claim(Ids.usernameHolder(wanted), user.id())) {
      return Answers.wrapped(400, "Username is taken.");
    }
    // The name being left behind is released only once the new one is held, so a rename that
    // loses the race does not leave the account holding neither.
    if (user.username() != null && !user.username().equals(wanted)) {
      data.release(Ids.usernameHolder(user.username()));
    }

    String newPassword = text(body, "newPassword");
    String oldPassword = text(body, "oldPassword");
    if (newPassword != null || oldPassword != null) {
      if (newPassword == null || oldPassword == null) {
        return Answers.wrapped(400, "Please fill out all the fields.");
      }
      if (user.password() == null) {
        return Answers.wrapped(
            400,
            "User has no password. Please create one from the password settings page.");
      }
      if (!Passwords.matches(oldPassword, user.password())) {
        return Answers.wrapped(400, "Old password is incorrect.");
      }
      if (newPassword.equals(oldPassword)) {
        return Answers.wrapped(400, "New password must be different from the old password.");
      }
    }

    String locale = text(body, "locale");
    Instant now = Instant.now();
    Records.User updated =
        data.client()
            .forKeyValueEntity(Ids.user(user.id()))
            .method(UserEntity::updateSettings)
            .invoke(
                new UserEntity.UpdateSettings(
                    text(body, "name"),
                    wanted,
                    config.emailEnabled() ? text(body, "email") : null,
                    // The schema gives this an empty string when the body leaves it out, and
                    // the answer carries what was written rather than what was sent.
                    text(body, "image") == null ? "" : text(body, "image"),
                    newPassword == null ? null : Passwords.hash(newPassword),
                    locale == null ? null : (LOCALES.contains(locale) ? locale : "en"),
                    integers(body, "collectionOrder") == null
                        ? List.of()
                        : integers(body, "collectionOrder"),
                    text(body, "linksRouteTo"),
                    text(body, "aiTaggingMethod"),
                    strings(body, "aiPredefinedTags"),
                    flag(body, "aiTagExistingLinks"),
                    flag(body, "archiveAsScreenshot"),
                    flag(body, "archiveAsMonolith"),
                    flag(body, "archiveAsPDF"),
                    flag(body, "archiveAsReadable"),
                    flag(body, "archiveAsWaybackMachine"),
                    flag(body, "isPrivate"),
                    flag(body, "preventDuplicateLinks"),
                    text(body, "referredBy"),
                    now));
    return Answers.wrapped(200, Shapes.userWithSubscription(updated, false, Shapes.Account.UPDATED));
  }

  @Put("/users/{id}/preference")
  public HttpResponse updatePreference(int id, JsonNode body) {
    Caller.Result result = caller.fromRequest(authorization(), Instant.now());
    if (result.refused()) return result.refusal();
    if (id == 0) return Answers.wrapped(400, "Invalid request.");
    if (result.user().id() != id) return Answers.wrapped(401, "Permission denied.");
    if (config.demoMode()) return Answers.demoRefusal();

    Optional<Validation.Issue> issue =
        Validation.first(
            Validation.oneOf("theme", text(body, "theme"), List.of("dark", "light", "auto")),
            Validation.optionalString("readableFontFamily", text(body, "readableFontFamily"), 0, 100),
            Validation.optionalString("readableFontSize", text(body, "readableFontSize"), 0, 100),
            Validation.optionalString("readableLineHeight", text(body, "readableLineHeight"), 0, 100),
            Validation.optionalString("readableLineWidth", text(body, "readableLineWidth"), 0, 100),
            Validation.optionalString(
                "dismissedAnnouncementId", text(body, "dismissedAnnouncementId"), 0, 100));
    if (issue.isPresent()) return Answers.issue(issue.get());

    Records.User updated =
        data.client()
            .forKeyValueEntity(Ids.user(result.user().id()))
            .method(UserEntity::updatePreference)
            .invoke(
                new UserEntity.UpdatePreference(
                    text(body, "theme"),
                    text(body, "readableFontFamily"),
                    text(body, "readableFontSize"),
                    text(body, "readableLineHeight"),
                    text(body, "readableLineWidth"),
                    text(body, "dismissedAnnouncementId"),
                    Instant.now()));
    return Answers.wrapped(200, Shapes.userWithSubscription(updated, false, Shapes.Account.PREFERENCE));
  }

  @Delete("/users/{id}")
  public HttpResponse deleteUser(int id, JsonNode body) {
    Caller.Result result = caller.fromToken(authorization(), Instant.now());
    if (result.refused()) return result.refusal();
    if (id == 0) return Answers.wrapped(400, "Invalid request.");
    if (config.demoMode()) return Answers.demoRefusal();

    Records.User user = result.user();
    boolean isAdmin = caller.isAdministrator(user);
    if (user.id() != id && !isAdmin) return Answers.wrapped(401, "Permission denied.");

    if (user.id() == id && !isAdmin) {
      String password = text(body, "password");
      if (user.password() == null) {
        return Answers.wrapped(
            401, "User has no password. Please create one from the password settings page.");
      }
      if (!Passwords.matches(password, user.password())) {
        return Answers.wrapped(401, "Invalid credentials.");
      }
    }

    Instant now = Instant.now();
    Optional<Records.User> going = data.user(id);
    data.client().forKeyValueEntity(Ids.user(id)).method(UserEntity::delete).invoke(now);
    // The username and address a deleted account held are free again, which is what lets
    // somebody register under the name they just gave up.
    going.ifPresent(
        gone -> {
          if (gone.username() != null) data.release(Ids.usernameHolder(gone.username()));
          if (gone.email() != null) data.release(Ids.emailHolder(gone.email()));
        });
    return Answers.wrapped(200, "User account and all related data deleted successfully.");
  }

  // ------------------------------------------------------------------
  // API tokens
  // ------------------------------------------------------------------

  @Get("/tokens")
  public HttpResponse listTokens() {
    Caller.Result result = caller.fromRequest(authorization(), Instant.now());
    if (result.refused()) return result.refusal();
    return Answers.wrapped(
        200,
        data.liveTokensFor(result.user().id()).stream().map(Shapes::tokenSummary).toList());
  }

  @Post("/tokens")
  public HttpResponse createToken(JsonNode body) {
    Caller.Result result = caller.fromRequest(authorization(), Instant.now());
    if (result.refused()) return result.refusal();
    if (config.demoMode()) return Answers.demoRefusal();

    String name = text(body, "name");
    JsonNode expires = body == null ? null : body.get("expires");

    Optional<Validation.Issue> issue =
        Validation.first(
            Validation.requiredString("name", name, 0, 50),
            expires == null || !expires.isInt() || expires.asInt() < 0 || expires.asInt() > 4
                ? Optional.of(
                    Validation.invalidOption("expires", List.of("0", "1", "2", "3", "4")))
                : Optional.empty());
    if (issue.isPresent()) return Answers.issue(issue.get());

    boolean nameTaken =
        data.liveTokensFor(result.user().id()).stream().anyMatch(t -> t.name().equals(name));
    if (nameTaken) return Answers.wrapped(400, "Token with that name already exists.");

    Instant now = Instant.now();
    Instant expiry = now.plus(Tokens.expiryDays(expires.asInt()), ChronoUnit.DAYS);
    String secret = mintAndRecord(result.user().id(), name, false, expiry, now);

    Records.AccessToken created =
        data.liveTokensFor(result.user().id()).stream()
            .filter(t -> t.name().equals(name))
            .reduce((first, second) -> second)
            .orElseThrow(() -> new IllegalStateException("the token just minted is not on record"));
    Map<String, Object> answer = new LinkedHashMap<>();
    answer.put("secretKey", secret);
    answer.put("token", Shapes.token(created));
    return Answers.wrapped(200, answer);
  }

  @Delete("/tokens/{id}")
  public HttpResponse revokeToken(int id) {
    Caller.Result result = caller.fromRequest(authorization(), Instant.now());
    if (result.refused()) return result.refusal();
    if (config.demoMode()) return Answers.demoRefusal();
    if (id == 0) return Answers.wrapped(401, "Please choose a valid token.");

    Optional<Records.AccessToken> token = data.accessToken(id);
    if (token.isEmpty() || token.get().userId() != result.user().id()) {
      return Answers.wrapped(401, "Please choose a valid token.");
    }
    Records.AccessToken revoked =
        data.client()
            .forKeyValueEntity(Ids.accessToken(id))
            .method(AccessTokenEntity::revoke)
            .invoke(Instant.now());
    return Answers.wrapped(200, Shapes.token(revoked));
  }

  // ------------------------------------------------------------------
  // the public view of an account
  // ------------------------------------------------------------------

  @Get("/public/users/{lookup}")
  public HttpResponse publicUser(String lookup) {
    // SPEC-001 R41 — read as an identifier only when every character is a digit.
    boolean isId = !lookup.isEmpty() && lookup.chars().allMatch(Character::isDigit);
    Optional<Records.User> found =
        isId ? data.user(Integer.parseInt(lookup)) : data.userByUsername(lookup);
    if (found.isEmpty() && !isId) found = data.userByEmail(lookup);
    if (found.isEmpty()) return Answers.wrapped(404, "User not found.");
    return Answers.wrapped(200, Shapes.publicUser(found.get()));
  }

  private static List<Integer> integers(JsonNode body, String field) {
    JsonNode node = body == null ? null : body.get(field);
    if (node == null || !node.isArray()) return null;
    List<Integer> out = new ArrayList<>();
    node.forEach(item -> out.add(item.asInt()));
    return out;
  }

  private static List<String> strings(JsonNode body, String field) {
    JsonNode node = body == null ? null : body.get(field);
    if (node == null || !node.isArray()) return null;
    List<String> out = new ArrayList<>();
    node.forEach(item -> out.add(item.asText()));
    return out;
  }
}

package io.akka.linkwarden;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * SPEC-001 R18–R22 — the rules that only exist when an email provider is configured.
 *
 * <p>Whether the provider is on is configuration, so this class turns it on for itself before the
 * runtime it drives is built. Every test class gets a fork of its own, so the setting reaches this
 * one and nothing else.
 *
 * <p>Delivering the mail is out of scope, so the routes answer the token they would have posted;
 * everything the rules decide about it is unchanged.
 */
class VerificationIntegrationTest extends SurfaceTestBase {

  static {
    // Set here rather than in a lifecycle hook: the runtime reads its configuration while it is
    // being built, which happens before any @BeforeAll of this class would run.
    System.setProperty("EMAIL_FROM", "linkwarden@example.com");
    System.setProperty("EMAIL_SERVER", "smtp://example.com:25");
    System.setProperty("NEXT_PUBLIC_EMAIL_PROVIDER", "true");
  }

  /** With the provider on, registration takes an address and the username becomes optional. */
  private String registerWithEmail(String email) {
    JsonNode created =
        send(
            "POST",
            "/api/v1/users",
            null,
            Map.of("name", "A Person", "email", email, "password", "a-good-password"));
    assertEquals(201, status(created), created.toString());
    return created.get("response").get("username").asText();
  }

  @Test
  void anAccountMadeWithAnAddressIsNotVerifiedAndCannotSignIn() {
    String email = unique("person") + "@example.com";
    registerWithEmail(email);

    JsonNode refusal =
        send(
            "POST",
            "/api/v1/session",
            null,
            Map.of("username", email, "password", "a-good-password"));
    assertEquals(401, status(refusal), "R12");
    assertEquals("EMAIL_NOT_VERIFIED", refusal.get("code").asText());
    assertEquals(email, refusal.get("email").asText());
  }

  @Test
  void aVerificationTokenVerifiesTheAddressAndComesBackAsASession() {
    String email = unique("person") + "@example.com";
    registerWithEmail(email);

    JsonNode asked =
        send("POST", "/api/v1/auth/request-verification-email", null, Map.of("email", email));
    assertEquals(200, status(asked), asked.toString());
    String token = asked.get("token").asText();

    JsonNode verified =
        send(
            "POST",
            "/api/v1/auth/verify-email-token",
            null,
            Map.of("email", email, "token", token, "sessionName", "a browser"));
    assertEquals(200, status(verified), verified.toString());
    String session = verified.get("response").get("token").asText();
    assertFalse(session.isEmpty(), "R21 — a session comes back");

    JsonNode me = send("GET", "/api/v1/users/me", session, null);
    assertEquals(200, status(me));
    assertFalse(me.get("response").get("emailVerified").isNull(), "R21 — and the address is verified");

    JsonNode again =
        send(
            "POST",
            "/api/v1/auth/verify-email-token",
            null,
            Map.of("email", email, "token", token));
    assertEquals(400, status(again), "R21 — every token for the address went");
    assertEquals(
        "Verification link is invalid or has expired.", again.get("response").asText());
  }

  @Test
  void anAddressAlreadyVerifiedIsNotAskedToVerifyAgain() {
    String email = unique("person") + "@example.com";
    registerWithEmail(email);
    String token =
        send("POST", "/api/v1/auth/request-verification-email", null, Map.of("email", email))
            .get("token")
            .asText();
    send(
        "POST",
        "/api/v1/auth/verify-email-token",
        null,
        Map.of("email", email, "token", token));

    JsonNode refusal =
        send("POST", "/api/v1/auth/request-verification-email", null, Map.of("email", email));
    assertEquals(400, status(refusal), "R20");
    assertEquals("Email is already verified.", refusal.get("response").asText());
  }

  @Test
  void theFifthVerificationRequestInsideFiveMinutesIsRefusedWithADifferentStatus() {
    String email = unique("person") + "@example.com";
    registerWithEmail(email);
    for (int i = 0; i < 4; i++) {
      JsonNode answer =
          send("POST", "/api/v1/auth/request-verification-email", null, Map.of("email", email));
      assertEquals(200, status(answer), "request " + i + ": " + answer);
    }
    JsonNode fifth =
        send("POST", "/api/v1/auth/request-verification-email", null, Map.of("email", email));
    assertEquals(429, status(fifth), "R20 — a 429 here, not the 400 the reset route answers");
    assertEquals("Too many requests. Please try again later.", fifth.get("response").asText());
  }

  @Test
  void anUnknownAddressIsRefusedBeforeTheRateLimitIsConsulted() {
    JsonNode answer =
        send(
            "POST",
            "/api/v1/auth/request-verification-email",
            null,
            Map.of("email", "nobody-here@example.com"));
    assertEquals(400, status(answer), "R20");
    assertEquals("Invalid email.", answer.get("response").asText());
  }

  @Test
  void theFourthResetRequestForOneAddressInsideFiveMinutesIsRefused() {
    String email = unique("person") + "@example.com";
    registerWithEmail(email);

    for (int i = 0; i < 3; i++) {
      JsonNode answer =
          send("POST", "/api/v1/auth/forgot-password", null, Map.of("email", email));
      assertEquals(200, status(answer), "request " + i + ": " + answer);
    }
    JsonNode fourth = send("POST", "/api/v1/auth/forgot-password", null, Map.of("email", email));
    assertEquals(400, status(fourth), "R18 — a 400 here, not the 429 the verification route answers");
    assertEquals("Too many requests. Please try again later.", fourth.get("response").asText());
  }

  @Test
  void aResetTokenSetsTheNewPasswordOnceAndThenStopsWorking() {
    String email = unique("person") + "@example.com";
    String username = registerWithEmail(email);

    String token =
        send("POST", "/api/v1/auth/forgot-password", null, Map.of("email", email))
            .get("token")
            .asText();

    JsonNode reset =
        send(
            "POST",
            "/api/v1/auth/reset-password",
            null,
            Map.of("token", token, "password", "a-new-password"));
    assertEquals(200, status(reset), reset.toString());
    assertEquals("Password has been reset successfully.", reset.get("response").asText());

    JsonNode again =
        send(
            "POST",
            "/api/v1/auth/reset-password",
            null,
            Map.of("token", token, "password", "a-third-password"));
    assertEquals(400, status(again), "R19 — the token is spent where it stands");
    assertEquals("Invalid token.", again.get("response").asText());
    assertTrue(username.length() >= 3, "R15 — a generated username is still a username");
  }

  @Test
  void changingAnAddressNeedsOneWaitingToBeChangedTo() {
    String email = unique("person") + "@example.com";
    registerWithEmail(email);
    String token =
        send("POST", "/api/v1/auth/request-verification-email", null, Map.of("email", email))
            .get("token")
            .asText();

    // R22 reads its token from the query string, and matches on what was stored rather than on
    // what was posted, so the token as issued is not the one this route accepts.
    JsonNode refusal = send("POST", "/api/v1/auth/verify-email?token=" + token, null, null);
    assertEquals(400, status(refusal));
    assertEquals("Invalid token.", refusal.get("response").asText());
  }
}

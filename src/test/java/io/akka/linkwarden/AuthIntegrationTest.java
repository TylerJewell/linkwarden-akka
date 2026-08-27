package io.akka.linkwarden;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * SPEC-001 R12, R18–R19 — signing in and resetting a password.
 *
 * <p>The email provider is off in this run, which is the ordinary configuration, so the three
 * verification routes answer that it is not configured; what they do when it is on is checked by
 * {@link VerificationIntegrationTest}, which runs with it turned on.
 */
class AuthIntegrationTest extends SurfaceTestBase {

  @Test
  void theWrongPasswordAndAnUnknownAccountAreTheSameAnswer() {
    Account account = register();
    JsonNode wrongPassword =
        send(
            "POST",
            "/api/v1/session",
            null,
            Map.of("username", account.username(), "password", "not-the-password"));
    JsonNode noSuchAccount =
        send(
            "POST",
            "/api/v1/session",
            null,
            Map.of("username", "nobody-at-all", "password", "not-the-password"));
    assertEquals(400, status(wrongPassword), "R12");
    assertEquals(400, status(noSuchAccount));
    assertEquals(
        wrongPassword.get("response").asText(),
        noSuchAccount.get("response").asText(),
        "R12 — one sentence for both");
  }

  @Test
  void aSessionIsNamedAndDefaultsToAnUnknownDevice() {
    Account account = register();
    JsonNode named =
        send(
            "POST",
            "/api/v1/session",
            null,
            Map.of("username", account.username(), "password", "a-good-password"));
    assertEquals(200, status(named));

    JsonNode tokens = send("GET", "/api/v1/tokens", account.token(), null);
    boolean sawUnknownDevice = false;
    for (JsonNode token : tokens.get("response")) {
      if (token.get("name").asText().equals("Unknown Device")) sawUnknownDevice = true;
    }
    assertTrue(sawUnknownDevice, "R12 — a session with no name is an Unknown Device: " + tokens);
  }

  @Test
  void anUnknownAddressIsToldSoRatherThanBeingHiddenBehindASuccess() {
    JsonNode answer =
        send("POST", "/api/v1/auth/forgot-password", null, Map.of("email", "nobody@example.com"));
    assertEquals(400, status(answer), "R18");
    assertEquals("No user found with that email.", answer.get("response").asText());
  }

  @Test
  void aTokenNobodyIssuedIsRefused() {
    JsonNode answer =
        send(
            "POST",
            "/api/v1/auth/reset-password",
            null,
            Map.of("token", "made-up-token", "password", "a-good-password"));
    assertEquals(400, status(answer));
    assertEquals("Invalid token.", answer.get("response").asText());
  }

  @Test
  void withoutAnEmailProviderTheVerificationRoutesSaySo() {
    for (String route : new String[] {"request-verification-email", "verify-email-token"}) {
      JsonNode answer =
          send("POST", "/api/v1/auth/" + route, null, Map.of("email", "somebody@example.com"));
      assertEquals(400, status(answer), route);
      assertEquals("Email is not configured.", answer.get("response").asText(), route);
    }
  }
}

package io.akka.linkwarden;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** SPEC-001 R9–R14 — who a request is, and the tokens that say so. */
class TokenIntegrationTest extends SurfaceTestBase {

  @Test
  void aTokenIsMintedOnceAndDisclosedOnceOnly() {
    Account account = register();
    JsonNode made =
        send("POST", "/api/v1/tokens", account.token(), Map.of("name", "A tool", "expires", 0));
    assertEquals(200, status(made), made.toString());
    String secret = made.get("response").get("secretKey").asText();
    assertFalse(secret.isEmpty(), "R13 — the token itself, this once");

    JsonNode listed = send("GET", "/api/v1/tokens", account.token(), null);
    assertEquals(200, status(listed));
    for (JsonNode token : listed.get("response")) {
      assertFalse(token.has("token"), "R14 — the listing discloses no identifier");
      assertFalse(token.has("secretKey"));
    }
  }

  @Test
  void aSecondTokenOfTheSameNameIsRefused() {
    Account account = register();
    send("POST", "/api/v1/tokens", account.token(), Map.of("name", "Same name", "expires", 0));
    JsonNode second =
        send("POST", "/api/v1/tokens", account.token(), Map.of("name", "Same name", "expires", 0));
    assertEquals(400, status(second), "R13");
    assertEquals("Token with that name already exists.", second.get("response").asText());
  }

  @Test
  void anIssuedTokenActsAsTheAccountAndStopsWhenItIsRevoked() {
    Account account = register();
    JsonNode made =
        send("POST", "/api/v1/tokens", account.token(), Map.of("name", "A tool", "expires", 0));
    String issued = made.get("response").get("secretKey").asText();
    int id = made.get("response").get("token").get("id").asInt();

    JsonNode asTool = send("GET", "/api/v1/users/me", issued, null);
    assertEquals(200, status(asTool), "R9 — the bearer header alone decides who is asking");
    assertEquals(account.id(), asTool.get("response").get("id").asInt());

    JsonNode revoked = send("DELETE", "/api/v1/tokens/" + id, account.token(), null);
    assertEquals(200, status(revoked));

    JsonNode afterwards = send("GET", "/api/v1/users/me", issued, null);
    assertEquals(401, status(afterwards), "R10");
    assertEquals(
        "Your session has expired, please log in again.", afterwards.get("response").asText());
  }

  @Test
  void aRevokedTokenIsKeptRatherThanDeletedAndDropsOutOfTheListing() {
    Account account = register();
    JsonNode made =
        send("POST", "/api/v1/tokens", account.token(), Map.of("name", "Going", "expires", 0));
    int id = made.get("response").get("token").get("id").asInt();
    send("DELETE", "/api/v1/tokens/" + id, account.token(), null);

    JsonNode listed = send("GET", "/api/v1/tokens", account.token(), null);
    for (JsonNode token : listed.get("response")) {
      assertTrue(token.get("id").asInt() != id, "R14 — an unrevoked listing");
    }
  }

  @Test
  void aTokenThatIsNotOursIsRefusedAsNotSignedInRatherThanAsExpired() {
    JsonNode answer = send("GET", "/api/v1/users/me", "not-a-real-token", null);
    assertEquals(401, status(answer));
    assertEquals("You must be logged in.", answer.get("response").asText(), "R10");
  }
}

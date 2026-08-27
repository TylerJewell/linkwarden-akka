package io.akka.linkwarden;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** SPEC-001 R15–R17 — who may open an account, and what a new one carries. */
class RegistrationIntegrationTest extends SurfaceTestBase {

  @Test
  void aNewAccountCarriesItsWholeRecordMinusThePassword() {
    JsonNode created =
        send(
            "POST",
            "/api/v1/users",
            null,
            Map.of("name", "A Person", "username", unique("fresh"), "password", "a-good-password"));
    assertEquals(201, status(created), "R16 — a 201, not a 200");

    JsonNode user = created.get("response");
    assertFalse(user.has("password"), "R16 — the password never leaves");
    assertTrue(user.get("archiveAsScreenshot").asBoolean(), "R16 — four of the five default on");
    assertTrue(user.get("archiveAsMonolith").asBoolean());
    assertTrue(user.get("archiveAsPDF").asBoolean());
    assertTrue(user.get("archiveAsReadable").asBoolean());
    assertFalse(user.get("archiveAsWaybackMachine").asBoolean(), "R16 — and the fifth off");
    assertEquals("dark", user.get("theme").asText());
    assertEquals("DISABLED", user.get("aiTaggingMethod").asText());
    assertTrue(user.get("emailVerified").isNull(), "R16 — verified only when an admin made it");
  }

  @Test
  void theThreeDashboardSectionsAreThereFromTheStart() {
    Account account = register();
    JsonNode me = send("GET", "/api/v1/users/me", account.token(), null);
    JsonNode sections = me.get("response").get("dashboardSections");
    assertEquals(3, sections.size(), "R16");
    assertEquals(
        List.of("STATS", "RECENT_LINKS", "PINNED_LINKS"),
        List.of(
            sections.get(0).get("type").asText(),
            sections.get(1).get("type").asText(),
            sections.get(2).get("type").asText()));
    assertEquals(0, sections.get(0).get("order").asInt());
    assertEquals(1, sections.get(1).get("order").asInt());
    assertEquals(2, sections.get(2).get("order").asInt());
  }

  @Test
  void aUsernameIsComparedWithoutRegardForCase() {
    String username = unique("Mixed");
    send(
        "POST",
        "/api/v1/users",
        null,
        Map.of("name", "First", "username", username.toLowerCase(), "password", "a-good-password"));
    JsonNode second =
        send(
            "POST",
            "/api/v1/users",
            null,
            Map.of("name", "Second", "username", username.toUpperCase(), "password", "a-good-password"));
    assertEquals(400, status(second), "R16");
    assertEquals("Email or Username already exists.", second.get("response").asText());
  }

  @Test
  void aRegistrationWithNoPasswordIsRefusedBeforeTheSchemaSpeaks() {
    JsonNode refusal =
        send("POST", "/api/v1/users", null, Map.of("name", "A Person", "username", unique("nopass")));
    assertEquals(400, status(refusal));
    assertEquals("Password is required.", refusal.get("response").asText(), "R15");
  }

  @Test
  void aUsernameOutsideThePatternIsRefusedByTheSchema() {
    JsonNode refusal =
        send(
            "POST",
            "/api/v1/users",
            null,
            Map.of("name", "A Person", "username", "has spaces", "password", "a-good-password"));
    assertEquals(400, status(refusal));
    assertTrue(refusal.get("response").asText().endsWith("[username]"), refusal.toString());
  }
}

package io.akka.linkwarden;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * SPEC-001 R5–R8 — what the surface answers before any rule about the records behind it.
 *
 * <p>Runs against a started runtime and over HTTP, which is the only route in from outside the
 * port's own tests, and is the level at which the two envelopes and the refusals are decided.
 */
class SurfaceIntegrationTest extends SurfaceTestBase {

  @Test
  void theConfigurationRouteAnswersToACallerWhoIsNobody() {
    JsonNode answer = send("GET", "/api/v1/config", null, null);
    assertEquals(200, status(answer));
    JsonNode published = answer.get("response");
    assertEquals(17, published.size(), "R1 — seventeen keys, no more and no fewer");
    assertTrue(
        published.get("ADMIN").isNull(),
        "R1 — an unset number reads null rather than its own default");
    assertEquals("v2.16.1", published.get("INSTANCE_VERSION").asText());
  }

  @Test
  void theLoginsRouteAnswersStringsRatherThanBooleans() {
    JsonNode answer = send("GET", "/api/v1/logins", null, null);
    assertEquals(200, status(answer));
    assertEquals(
        "true",
        answer.get("credentialsEnabled").asText(),
        "R2 — the flag is the string, not the boolean, and the answer carries no envelope");
  }

  @Test
  void aProtectedRouteWithoutATokenSaysSoRatherThanAnsweringEmpty() {
    JsonNode answer = send("GET", "/api/v1/links", null, null);
    assertEquals(401, status(answer));
    assertEquals("You must be logged in.", answer.get("response").asText());
  }

  @Test
  void aValidationFailureCarriesTheFirstIssueOnlyAndNamesItsPath() {
    JsonNode answer =
        send("POST", "/api/v1/session", null, Map.of("username", "ab", "password", "short"));
    assertEquals(400, status(answer), "R7 — a body that fails the schema is a 400");
    String rendered = answer.get("response").asText();
    assertTrue(rendered.startsWith("Error: "), "R7 — the message is the rendered issue: " + rendered);
    assertTrue(rendered.endsWith("[username]"), "R7 — the first issue in field order: " + rendered);
  }

  @Test
  void mostRoutesWrapTheirAnswerAndTheListingRoutesEnvelopeIt() {
    Account account = register();

    JsonNode wrapped = send("GET", "/api/v1/collections", account.token(), null);
    assertEquals(200, status(wrapped));
    assertNotNull(wrapped.get("response"), "R6 — a collection list is wrapped");

    JsonNode enveloped = send("GET", "/api/v1/tags", account.token(), null);
    assertEquals(200, status(enveloped));
    assertNotNull(enveloped.get("data"), "R6 — the tag list is enveloped instead");
    assertTrue(enveloped.get("success").asBoolean());
    assertEquals("Success", enveloped.get("message").asText());

    JsonNode search = send("GET", "/api/v1/search", account.token(), null);
    assertNotNull(search.get("data"), "R6 — and so is the search");
    JsonNode dashboard = send("GET", "/api/v2/dashboard", account.token(), null);
    assertNotNull(dashboard.get("data"), "R6 — and the second dashboard");
    JsonNode firstDashboard = send("GET", "/api/v1/dashboard", account.token(), null);
    assertNotNull(firstDashboard.get("response"), "R6 — but not the first one");
  }

  @Test
  void everyRouteTheSpecificationNamesIsServed() {
    Account account = register();
    // A route nobody serves answers 404 with an empty body. A served route answers something
    // else, or a 404 that carries a sentence — a collection that is not public and an account
    // with no picture are both ordinary answers with that status.
    for (String path :
        new String[] {
          "/api/v1/config",
          "/api/v1/logins",
          "/api/v1/users/me",
          "/api/v1/collections",
          "/api/v1/links",
          "/api/v1/tags",
          "/api/v1/search",
          "/api/v1/dashboard",
          "/api/v2/dashboard",
          "/api/v1/rss",
          "/api/v1/migration",
          "/api/v1/tokens",
          "/api/v1/worker",
          "/api/v1/preserved/token",
          "/api/v1/preserved/view",
          "/api/v1/getFavicon?url=https%3A%2F%2Fexample.com",
          "/api/v1/public/collections/links?collectionId=1",
          "/api/v1/public/collections/tags?collectionId=1",
          "/api/v1/public/users/" + account.username()
        }) {
      JsonNode answer = send("GET", path, account.token(), null);
      assertTrue(
          status(answer) != 404 || answer.size() > 1, path + " is not served: " + answer);
    }
    // The avatar route is the one that answers 404 when it is working: an account with no
    // picture has no file, and R90 makes that the ordinary answer rather than an error.
    JsonNode avatar = send("GET", "/api/v1/avatar/" + account.id(), account.token(), null);
    assertEquals(404, status(avatar));
    assertEquals("File not found.", avatar.get("__body").asText());
  }
}

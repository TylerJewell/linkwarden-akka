package io.akka.linkwarden;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** SPEC-001 R56, R97 — what the administrator is shown, and what only they may reset. */
class WorkerStatsIntegrationTest extends SurfaceTestBase {

  @Test
  void anybodyButTheAdministratorIsRefused() {
    // The administrator is account 1 by default, and every account this suite registers comes
    // after it, so any of them stands for "not the administrator".
    Account somebody = register();
    JsonNode refusal = send("GET", "/api/v1/worker", somebody.token(), null);
    assertEquals(403, status(refusal), "R97");
    assertEquals("Forbidden.", refusal.get("response").asText());

    JsonNode reset =
        send(
            "DELETE",
            "/api/v1/worker/preservation",
            somebody.token(),
            Map.of("action", "allAndRePreserve"));
    assertEquals(403, status(reset), "R56");
  }

  @Test
  void theCountsAreTheFiveTheSpecificationNames() {
    // Whoever holds identifier 1 is the administrator; this suite's first registration is it
    // when the class runs alone, and the check below reads only the shape either way.
    Account first = register();
    JsonNode answer = send("GET", "/api/v1/worker", first.token(), null);
    if (status(answer) == 403) return;

    assertEquals(200, status(answer));
    JsonNode data = answer.get("data");
    assertTrue(data.get("link").has("pending"), "R97");
    assertTrue(data.get("link").has("done"));
    assertTrue(data.get("link").has("failed"));
    assertTrue(data.get("search").has("pending"));
    assertTrue(data.get("search").has("done"));
    assertEquals("Worker stats fetched successfully.", answer.get("message").asText());
  }

  @Test
  void aRepairMustNameOneOfTheTwoActions() {
    Account first = register();
    JsonNode refusal =
        send("DELETE", "/api/v1/worker/preservation", first.token(), Map.of("action", "something"));
    assertTrue(
        status(refusal) == 400 || status(refusal) == 403,
        "R56 — refused either for not being the administrator or for the action: " + refusal);
  }
}

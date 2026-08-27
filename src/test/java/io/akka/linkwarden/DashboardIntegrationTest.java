package io.akka.linkwarden;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** SPEC-001 R71–R72 — the two dashboards, and the layout that decides what the second one holds. */
class DashboardIntegrationTest extends SurfaceTestBase {

  private int save(Account account, String name, int collection) {
    return send(
            "POST",
            "/api/v1/links",
            account.token(),
            Map.of(
                "url", "https://d.invalid/" + name,
                "name", name,
                "collection", Map.of("id", collection)))
        .get("response")
        .get("id")
        .asInt();
  }

  private int collection(Account account, String name) {
    return send("POST", "/api/v1/collections", account.token(), Map.of("name", name))
        .get("response").get("id").asInt();
  }

  @Test
  void theFirstDashboardMergesPinnedAndRecentNewestFirstWithoutRepeating() {
    Account account = register();
    int box = collection(account, "Box");
    int first = save(account, "one", box);
    int second = save(account, "two", box);
    send(
        "PUT",
        "/api/v1/links/" + first,
        account.token(),
        Map.of("name", "one", "url", "https://d.invalid/one",
            "pinnedBy", List.of(Map.of("id", account.id()))));

    JsonNode answer = send("GET", "/api/v1/dashboard", account.token(), null);
    assertEquals(200, status(answer));
    List<Integer> ids = ids(answer.get("response"));
    assertEquals(List.of(second, first), ids, "R71 — newest identifier first, each once");
  }

  private static List<Integer> ids(JsonNode links) {
    List<Integer> ids = new ArrayList<>();
    links.forEach(link -> ids.add(link.get("id").asInt()));
    return ids;
  }

  @Test
  void theSecondDashboardCountsPinsAndTags() {
    Account account = register();
    int box = collection(account, "Box");
    int link = save(account, "counted", box);
    send(
        "POST",
        "/api/v1/tags",
        account.token(),
        Map.of("tags", List.of(Map.of("label", "One"), Map.of("label", "Two"))));
    send(
        "PUT",
        "/api/v1/links/" + link,
        account.token(),
        Map.of("name", "counted", "url", "https://d.invalid/counted",
            "pinnedBy", List.of(Map.of("id", account.id()))));

    JsonNode answer = send("GET", "/api/v2/dashboard", account.token(), null);
    assertEquals(200, status(answer));
    assertEquals(1, answer.get("data").get("numberOfPinnedLinks").asInt(), "R71");
    assertEquals(2, answer.get("data").get("numberOfTags").asInt(), "R71");
    assertTrue(answer.get("data").has("collectionLinks"));
  }

  @Test
  void aLayoutWithNoLinkSectionAnswersAnEmptyListAndStopsThere() {
    Account account = register();
    int box = collection(account, "Box");
    save(account, "hidden", box);

    JsonNode answer =
        send(
            "PUT",
            "/api/v2/dashboard",
            account.token(),
            List.of(Map.of("type", "STATS", "enabled", true, "order", 0)));
    assertEquals(200, status(answer), answer.toString());
    assertEquals(0, answer.get("data").get("links").size(), "R71 — the short circuit");
    assertTrue(
        answer.get("data").get("collectionLinks") == null,
        "R71 — and the per-collection map is not there at all");
  }

  @Test
  void aSectionNamingACollectionTheCallerCannotReachIsDroppedRatherThanRefused() {
    Account account = register();
    Account stranger = register();
    int theirs = collection(stranger, "Theirs");
    int mine = collection(account, "Mine");
    int link = save(account, "seen", mine);

    JsonNode answer =
        send(
            "PUT",
            "/api/v2/dashboard",
            account.token(),
            List.of(
                Map.of("type", "COLLECTION", "collectionId", theirs, "enabled", true, "order", 0),
                Map.of("type", "COLLECTION", "collectionId", mine, "enabled", true, "order", 1)));
    assertEquals(200, status(answer), "R72 — dropped, not refused");

    JsonNode collectionLinks = answer.get("data").get("collectionLinks");
    assertTrue(collectionLinks.has(String.valueOf(mine)));
    assertTrue(!collectionLinks.has(String.valueOf(theirs)), "R72");
    assertEquals(link, collectionLinks.get(String.valueOf(mine)).get(0).get("id").asInt());
  }

  @Test
  void theLayoutReplacesWhatWasThereRatherThanAddingToIt() {
    Account account = register();
    send(
        "PUT",
        "/api/v2/dashboard",
        account.token(),
        List.of(
            Map.of("type", "RECENT_LINKS", "enabled", true, "order", 0),
            Map.of("type", "PINNED_LINKS", "enabled", false, "order", 1)));

    JsonNode me = send("GET", "/api/v1/users/me", account.token(), null);
    JsonNode sections = me.get("response").get("dashboardSections");
    assertEquals(1, sections.size(), "R72 — every section the caller had is deleted first");
    assertEquals("RECENT_LINKS", sections.get(0).get("type").asText());
  }
}

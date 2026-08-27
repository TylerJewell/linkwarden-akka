package io.akka.linkwarden;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** SPEC-001 R66–R69 — tags, their counts, their order and what a rename or a merge touches. */
class TagIntegrationTest extends SurfaceTestBase {

  private int saveTagged(Account account, String url, String... tags) {
    List<Map<String, Object>> named = new ArrayList<>();
    for (String tag : tags) named.add(Map.of("name", tag));
    JsonNode saved =
        send("POST", "/api/v1/links", account.token(), Map.of("url", url, "tags", named));
    assertEquals(200, status(saved), saved.toString());
    return saved.get("response").get("id").asInt();
  }

  private static List<String> names(JsonNode enveloped) {
    List<String> names = new ArrayList<>();
    enveloped.get("data").get("tags").forEach(tag -> names.add(tag.get("name").asText()));
    return names;
  }

  @Test
  void aTagCarriesHowManyLinksHoldIt() {
    Account account = register();
    saveTagged(account, "https://t1.invalid/a", "Shared");
    saveTagged(account, "https://t1.invalid/b", "Shared");

    JsonNode listed = send("GET", "/api/v1/tags", account.token(), null);
    assertEquals(200, status(listed));
    JsonNode tag = listed.get("data").get("tags").get(0);
    assertEquals("Shared", tag.get("name").asText());
    assertEquals(2, tag.get("_count").get("links").asInt(), "R66");
    assertTrue(listed.get("data").get("nextCursor").isNull(), "R66 — a short page names no cursor");
  }

  @Test
  void theSearchIsCaseInsensitive() {
    Account account = register();
    saveTagged(account, "https://t2.invalid/a", "Photography");
    JsonNode found = send("GET", "/api/v1/tags?search=PHOTO", account.token(), null);
    assertEquals(List.of("Photography"), names(found));
  }

  @Test
  void theSixOrdersAreIdentifierNameAndLinkCount() {
    Account account = register();
    saveTagged(account, "https://t3.invalid/a", "Beta", "Alpha");
    saveTagged(account, "https://t3.invalid/b", "Alpha");

    // Beta was named first on the first link, so it holds the lower identifier.
    assertEquals(List.of("Beta", "Alpha"), names(sorted(account, 1)), "oldest first");
    assertEquals(List.of("Alpha", "Beta"), names(sorted(account, 0)), "newest first");
    assertEquals(List.of("Alpha", "Beta"), names(sorted(account, 2)), "name ascending");
    assertEquals(List.of("Beta", "Alpha"), names(sorted(account, 3)), "name descending");
    assertEquals(List.of("Alpha", "Beta"), names(sorted(account, 4)), "two links before one");
    assertEquals(List.of("Beta", "Alpha"), names(sorted(account, 5)), "one link before two");
  }

  private JsonNode sorted(Account account, int sort) {
    return send("GET", "/api/v1/tags?sort=" + sort, account.token(), null);
  }

  @Test
  void aCollectionsOwnTagsComeBackWithoutACursor() {
    Account account = register();
    int collection =
        send("POST", "/api/v1/collections", account.token(), Map.of("name", "Boxed"))
            .get("response").get("id").asInt();
    send(
        "POST",
        "/api/v1/links",
        account.token(),
        Map.of(
            "url", "https://t4.invalid/a",
            "collection", Map.of("id", collection),
            "tags", List.of(Map.of("name", "Zeta"), Map.of("name", "Alpha"))));

    JsonNode listed =
        send("GET", "/api/v1/tags?collectionId=" + collection, account.token(), null);
    assertEquals(List.of("Alpha", "Zeta"), names(listed), "R66 — by name, then identifier");
    assertTrue(listed.get("data").get("nextCursor") == null, "R66 — and no cursor at all");
  }

  @Test
  void renamingToANameAlreadyInUseIsRefused() {
    Account account = register();
    saveTagged(account, "https://t5.invalid/a", "First", "Second");
    JsonNode listed = send("GET", "/api/v1/tags", account.token(), null);
    int first = idOf(listed, "First");

    JsonNode refusal =
        send("PUT", "/api/v1/tags/" + first, account.token(), Map.of("name", "Second"));
    assertEquals(400, status(refusal), "R67");
    assertEquals("Tag names should be unique.", refusal.get("response").asText());
  }

  @Test
  void aTagSomebodyElseOwnsIsNotRenameable() {
    Account owner = register();
    Account stranger = register();
    saveTagged(owner, "https://t6.invalid/a", "Theirs");
    int id = idOf(send("GET", "/api/v1/tags", owner.token(), null), "Theirs");

    JsonNode refusal = send("PUT", "/api/v1/tags/" + id, stranger.token(), Map.of("name", "Mine"));
    assertEquals(401, status(refusal), "R67");
    assertEquals("Permission denied.", refusal.get("response").asText());
  }

  @Test
  void anUpsertWritesAllSixArchivalFieldsOnBothPaths() {
    Account account = register();
    JsonNode created =
        send(
            "POST",
            "/api/v1/tags",
            account.token(),
            Map.of(
                "tags",
                List.of(
                    Map.of(
                        "label", "Archival",
                        "archiveAsScreenshot", true,
                        "archiveAsMonolith", false,
                        "archiveAsPDF", true,
                        "archiveAsReadable", false,
                        "archiveAsWaybackMachine", true,
                        "aiTag", false))));
    assertEquals(200, status(created), created.toString());
    JsonNode tag = created.get("response").get(0);
    assertTrue(tag.get("archiveAsScreenshot").asBoolean(), "R69 — on the create path");
    assertTrue(tag.get("archiveAsWaybackMachine").asBoolean());

    JsonNode updated =
        send(
            "POST",
            "/api/v1/tags",
            account.token(),
            Map.of("tags", List.of(Map.of("label", "Archival", "archiveAsScreenshot", false))));
    assertEquals(
        false,
        updated.get("response").get(0).get("archiveAsScreenshot").asBoolean(),
        "R69 — and on the update path");
  }

  @Test
  void mergingCollectsTheLinksAndLeavesOneTagBehind() {
    Account account = register();
    int one = saveTagged(account, "https://t7.invalid/a", "Left");
    int two = saveTagged(account, "https://t7.invalid/b", "Right");
    JsonNode listed = send("GET", "/api/v1/tags", account.token(), null);

    JsonNode merged =
        send(
            "PUT",
            "/api/v1/tags/merge",
            account.token(),
            Map.of(
                "tagIds", List.of(idOf(listed, "Left"), idOf(listed, "Right")),
                "newTagName", "Both"));
    assertEquals(200, status(merged), merged.toString());

    assertEquals(
        List.of("Both"),
        names(send("GET", "/api/v1/tags", account.token(), null)),
        "R68 — the two named tags went and one took their place");
    for (int id : List.of(one, two)) {
      JsonNode link = send("GET", "/api/v1/links/" + id, account.token(), null);
      assertEquals(1, link.get("response").get("tags").size());
      assertEquals("Both", link.get("response").get("tags").get(0).get("name").asText());
    }
  }

  @Test
  void deletingATagTakesItOffEveryLinkThatCarriedIt() {
    Account account = register();
    int link = saveTagged(account, "https://t8.invalid/a", "Going", "Staying");
    int going = idOf(send("GET", "/api/v1/tags", account.token(), null), "Going");

    assertEquals(200, status(send("DELETE", "/api/v1/tags/" + going, account.token(), null)));
    JsonNode after = send("GET", "/api/v1/links/" + link, account.token(), null);
    assertEquals(List.of("Staying"), tagNames(after.get("response")));
  }

  private static List<String> tagNames(JsonNode link) {
    List<String> names = new ArrayList<>();
    link.get("tags").forEach(tag -> names.add(tag.get("name").asText()));
    return names;
  }

  private static int idOf(JsonNode enveloped, String name) {
    for (JsonNode tag : enveloped.get("data").get("tags")) {
      if (tag.get("name").asText().equals(name)) return tag.get("id").asInt();
    }
    throw new AssertionError("no tag called " + name + " in " + enveloped);
  }
}

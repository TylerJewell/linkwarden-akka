package io.akka.linkwarden;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** SPEC-001 R78–R83 — taking an account out, and bringing five foreign formats in. */
class MigrationIntegrationTest extends SurfaceTestBase {

  private static final String BOOKMARKS =
      """
      <!DOCTYPE NETSCAPE-Bookmark-file-1>
      <DL><p>
        <DT><H3>Reading</H3>
        <DL><p>
          <DT><A HREF="https://example.com/one" ADD_DATE="1700000000" TAGS="alpha,beta">One</A>
          <DD>The first page
          <DT><A HREF="https://example.com/two">Two</A>
        </DL><p>
        <DT><A HREF="https://example.com/loose">Loose</A>
      </DL><p>
      """;

  private List<String> collectionNames(Account account) {
    List<String> names = new ArrayList<>();
    send("GET", "/api/v1/collections", account.token(), null)
        .get("response")
        .forEach(collection -> names.add(collection.get("name").asText()));
    return names;
  }

  @Test
  void aBookmarkFileBecomesCollectionsAndLinks() {
    Account account = register();
    JsonNode imported =
        send("POST", "/api/v1/migration", account.token(), Map.of("format", 1, "data", BOOKMARKS));
    assertEquals(200, status(imported), imported.toString());

    List<String> names = collectionNames(account);
    assertTrue(names.contains("Reading"), "R79 — an H3 names a collection: " + names);
    assertTrue(names.contains("Imports"), "R79 — and a link outside one goes to Imports");

    JsonNode links = send("GET", "/api/v1/links", account.token(), null);
    List<String> saved = new ArrayList<>();
    links.get("response").forEach(link -> saved.add(link.get("name").asText()));
    assertTrue(saved.contains("One") && saved.contains("Two") && saved.contains("Loose"), saved.toString());
  }

  @Test
  void aBookmarksDescriptionTagsAndDateComeWithIt() {
    Account account = register();
    send("POST", "/api/v1/migration", account.token(), Map.of("format", 1, "data", BOOKMARKS));

    JsonNode links = send("GET", "/api/v1/links?searchQueryString=One", account.token(), null);
    JsonNode one = null;
    for (JsonNode link : links.get("response")) {
      if (link.get("name").asText().equals("One")) one = link;
    }
    assertTrue(one != null, "the imported link is there: " + links);
    assertEquals("", one.get("description").asText(), "R79 — the DD is not the anchor's child");
    assertEquals(2, one.get("tags").size(), "R79 — TAGS is comma-separated");
    assertFalse(one.get("importDate").isNull(), "R79 — ADD_DATE is seconds");
  }

  @Test
  void aFileThatIsNotTheFormatItClaimsIsRefused() {
    Account account = register();
    JsonNode refusal =
        send("POST", "/api/v1/migration", account.token(), Map.of("format", 0, "data", "not json"));
    assertEquals(400, status(refusal), "R83");
    assertEquals("Invalid request body provided.", refusal.get("response").asText());
  }

  @Test
  void aFormatNobodyDefinedIsRefused() {
    Account account = register();
    JsonNode refusal =
        send("POST", "/api/v1/migration", account.token(), Map.of("format", 9, "data", "{}"));
    assertEquals(400, status(refusal));
  }

  @Test
  void anExportCarriesTheAccountMinusItsPasswordItsIdentifierAndItsArchives() {
    Account account = register();
    int collection =
        send("POST", "/api/v1/collections", account.token(), Map.of("name", "Kept"))
            .get("response").get("id").asInt();
    send(
        "POST",
        "/api/v1/links",
        account.token(),
        Map.of(
            "url", "https://mi.invalid/a",
            "name", "A page",
            "collection", Map.of("id", collection)));

    JsonNode exported = send("GET", "/api/v1/migration", account.token(), null);
    assertEquals(200, status(exported), exported.toString());
    assertFalse(exported.has("password"), "R82");
    assertFalse(exported.has("id"), "R82");
    assertTrue(exported.has("collections"));
    assertTrue(exported.has("pinnedLinks"));

    JsonNode kept = null;
    for (JsonNode one : exported.get("collections")) {
      if (one.get("name").asText().equals("Kept")) kept = one;
    }
    assertTrue(kept != null, exported.toString());
    JsonNode link = kept.get("links").get(0);
    assertFalse(link.has("image"), "R82 — the five preservation fields are dropped");
    assertFalse(link.has("preview"));
    assertFalse(link.has("textContent"));
    assertEquals("A page", link.get("name").asText());
  }
}

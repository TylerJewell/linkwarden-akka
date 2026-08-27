package io.akka.linkwarden;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * SPEC-001 R64–R65 — searching without a search engine.
 *
 * <p>The query language of R60–R63 becomes filters an engine reads, and this instance ships
 * without one, so what a search does here is the fallback: four columns matched case-insensitively
 * and the combining rule of R41. The language itself is checked by {@code SearchQueryTest}.
 */
class SearchIntegrationTest extends SurfaceTestBase {

  private int save(Account account, String name, String url, int collection, String... tags) {
    List<Map<String, Object>> named = new ArrayList<>();
    for (String tag : tags) named.add(Map.of("name", tag));
    return send(
            "POST",
            "/api/v1/links",
            account.token(),
            Map.of(
                "url", url,
                "name", name,
                "description", "about " + name,
                "collection", Map.of("id", collection),
                "tags", named))
        .get("response")
        .get("id")
        .asInt();
  }

  private int collection(Account account, String name) {
    return send("POST", "/api/v1/collections", account.token(), Map.of("name", name))
        .get("response").get("id").asInt();
  }

  private static List<Integer> ids(JsonNode enveloped) {
    List<Integer> ids = new ArrayList<>();
    enveloped.get("data").get("links").forEach(link -> ids.add(link.get("id").asInt()));
    return ids;
  }

  @Test
  void aQueryMatchesTheNameTheAddressTheDescriptionOrATagName() {
    Account account = register();
    int box = collection(account, "Box");
    int byName = save(account, "Kingfisher", "https://se.invalid/a", box);
    int byUrl = save(account, "Nothing", "https://kingfisher.invalid/b", box);
    int byTag = save(account, "Another", "https://se.invalid/c", box, "kingfisher");

    List<Integer> found = ids(send("GET", "/api/v1/search?searchQueryString=kingfisher", account.token(), null));
    assertTrue(found.contains(byName), "R65 — the name");
    assertTrue(found.contains(byUrl), "R65 — the address");
    assertTrue(found.contains(byTag), "R65 — a tag's name");
  }

  @Test
  void theMatchIsMadeWithoutRegardForCase() {
    Account account = register();
    int box = collection(account, "Box");
    int link = save(account, "Peregrine", "https://se.invalid/d", box);
    assertTrue(
        ids(send("GET", "/api/v1/search?searchQueryString=PEREGRINE", account.token(), null))
            .contains(link),
        "R65");
  }

  @Test
  void aSearchSeesOnlyWhatTheCallerCanReach() {
    Account owner = register();
    Account stranger = register();
    int box = collection(owner, "Private");
    int hidden = save(owner, "Hidden thing", "https://se.invalid/e", box);

    assertTrue(
        !ids(send("GET", "/api/v1/search?searchQueryString=Hidden", stranger.token(), null))
            .contains(hidden),
        "R64");
  }

  @Test
  void anEmptySearchAnswersEverythingTheCallerCanReach() {
    Account account = register();
    int box = collection(account, "Box");
    int link = save(account, "Listed", "https://se.invalid/f", box);
    JsonNode all = send("GET", "/api/v1/search", account.token(), null);
    assertEquals(200, status(all));
    assertTrue(ids(all).contains(link));
    assertTrue(all.get("data").get("nextCursor").isNull(), "R65 — a short page names no cursor");
  }

  @Test
  void aCollectionNarrowsOnItsOwnAndAQueryBesideItDoesNotWiden() {
    Account account = register();
    int one = collection(account, "One");
    int two = collection(account, "Two");
    int inOne = save(account, "Wren", "https://se.invalid/g", one);
    int inTwo = save(account, "Wren", "https://se.invalid/h", two);

    List<Integer> narrowed =
        ids(send("GET", "/api/v1/search?collectionId=" + one, account.token(), null));
    assertTrue(narrowed.contains(inOne) && !narrowed.contains(inTwo), "R41 — by itself");

    List<Integer> withAQuery =
        ids(
            send(
                "GET",
                "/api/v1/search?collectionId=" + one + "&searchQueryString=Wren",
                account.token(),
                null));
    assertTrue(
        withAQuery.contains(inOne) && !withAQuery.contains(inTwo),
        "R41 — the collection narrows whatever else is asked: " + withAQuery);
  }

  @Test
  void aPinnedOnlySearchWithAQueryAnswersLinksThatAreNotPinned() {
    Account account = register();
    int box = collection(account, "Box");
    int notPinned = save(account, "Merlin", "https://se.invalid/i", box);

    List<Integer> found =
        ids(
            send(
                "GET",
                "/api/v1/search?pinnedOnly=true&searchQueryString=Merlin",
                account.token(),
                null));
    assertTrue(
        found.contains(notPinned),
        "R41 — with a text the pin and the words are alternatives: " + found);

    List<Integer> withoutAQuery =
        ids(send("GET", "/api/v1/search?pinnedOnly=true", account.token(), null));
    assertTrue(!withoutAQuery.contains(notPinned), "R41 — and without one the pin narrows");
  }
}

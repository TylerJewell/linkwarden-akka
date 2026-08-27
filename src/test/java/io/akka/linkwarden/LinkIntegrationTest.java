package io.akka.linkwarden;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * SPEC-001 R33–R47 — saving, moving, pinning and deleting links.
 *
 * <p>Every url here is one the guard refuses, so no test reaches the network. What that costs is
 * the fetched title and content type, which are R37's and R38's second clauses and are compared
 * against the original in the benchmark instead; what it buys is a suite whose answers do not
 * depend on somebody else's web server being up.
 */
class LinkIntegrationTest extends SurfaceTestBase {

  private static final String A = "https://a.invalid/one";
  private static final String B = "https://b.invalid/two";

  private JsonNode save(Account account, Map<String, Object> body) {
    return send("POST", "/api/v1/links", account.token(), body);
  }

  @Test
  void aLinkWithNoCollectionGoesToUnorganizedAndTheSecondReusesIt() {
    Account account = register();
    JsonNode first = save(account, Map.of("url", A));
    assertEquals(200, status(first), first.toString());
    int firstCollection = first.get("response").get("collectionId").asInt();

    JsonNode second = save(account, Map.of("url", B));
    assertEquals(
        firstCollection,
        second.get("response").get("collectionId").asInt(),
        "R34 — Unorganized is made once and reused");
    assertEquals(
        "Unorganized",
        first.get("response").get("collection").get("name").asText());
  }

  @Test
  void aCollectionNamedInTheBodyIsMadeAfreshEveryTime() {
    Account account = register();
    int one = save(account, Map.of("url", A, "collection", Map.of("name", "Reading")))
        .get("response").get("collectionId").asInt();
    int two = save(account, Map.of("url", B, "collection", Map.of("name", "Reading")))
        .get("response").get("collectionId").asInt();
    assertTrue(one != two, "R34 — a name that is not Unorganized is not looked up first");
  }

  @Test
  void aNamelessLinkFallsBackToItsOwnAddress() {
    Account account = register();
    JsonNode saved = save(account, Map.of("url", A));
    assertEquals(A, saved.get("response").get("name").asText(), "R37 — the url is the last resort");
  }

  @Test
  void aGivenNameWins() {
    Account account = register();
    JsonNode saved = save(account, Map.of("url", A, "name", "  Chosen  "));
    assertEquals("Chosen", saved.get("response").get("name").asText());
  }

  @Test
  void aLinkWithNoUrlKeepsTheTypeItWasGiven() {
    Account account = register();
    JsonNode saved = save(account, Map.of("type", "pdf", "name", "A file"));
    assertEquals("pdf", saved.get("response").get("type").asText(), "R38");
    assertTrue(saved.get("response").get("url").isNull());
  }

  @Test
  void aUrlTheGuardRefusesIsFinishedBeforeAnythingCanOfferIt() {
    Account account = register();
    JsonNode saved = save(account, Map.of("url", "ftp://files.example.com/x"));
    assertEquals(200, status(saved), saved.toString());
    JsonNode link = saved.get("response");
    assertEquals("unavailable", link.get("preview").asText(), "R39");
    assertEquals("unavailable", link.get("image").asText());
    assertEquals("unavailable", link.get("pdf").asText());
    assertEquals("unavailable", link.get("readable").asText());
    assertEquals("unavailable", link.get("monolith").asText());
    assertFalse(link.get("lastPreserved").isNull(), "R39 — and it is already finished");
  }

  @Test
  void aTagIsTrimmedAndBelongsToTheCollectionsOwner() {
    Account account = register();
    JsonNode saved = save(account, Map.of("url", A, "tags", List.of(Map.of("name", "  Alpha  "))));
    JsonNode tags = saved.get("response").get("tags");
    assertEquals(1, tags.size());
    assertEquals("Alpha", tags.get(0).get("name").asText(), "R40 — trimmed");
    assertEquals(account.id(), tags.get(0).get("ownerId").asInt(), "R40 — the collection's owner");
  }

  @Test
  void anUnparseableAddressIsRefusedByTheSchema() {
    Account account = register();
    JsonNode refusal = save(account, Map.of("url", "not a url"));
    assertEquals(400, status(refusal));
    assertTrue(refusal.get("response").asText().endsWith("[url]"), refusal.toString());
  }

  @Test
  void duplicatePreventionIsOffUntilItIsAskedForAndThenReadsBothSpellings() {
    Account account = register();
    save(account, Map.of("url", "https://dupe.invalid/x"));
    JsonNode allowed = save(account, Map.of("url", "https://dupe.invalid/x"));
    assertEquals(200, status(allowed), "R35 — off by default");

    send(
        "PUT",
        "/api/v1/users/" + account.id(),
        account.token(),
        Map.of("username", account.username(), "preventDuplicateLinks", true));

    JsonNode refusedWithSlash = save(account, Map.of("url", "https://dupe.invalid/x/"));
    assertEquals(409, status(refusedWithSlash), "R35 — a trailing slash is the same url");
    assertEquals("Link already exists", refusedWithSlash.get("response").asText());

    JsonNode refusedWithWww = save(account, Map.of("url", "https://www.dupe.invalid/x"));
    assertEquals(409, status(refusedWithWww), "R35 — and so is a www. prefix");
  }

  @Test
  void theFourSortOrdersAreIdentifierAndName() {
    Account account = register();
    int collection =
        send("POST", "/api/v1/collections", account.token(), Map.of("name", "Sorted"))
            .get("response").get("id").asInt();
    for (String name : List.of("Beta", "Alpha", "Gamma")) {
      save(account, Map.of("url", "https://s.invalid/" + name, "name", name,
          "collection", Map.of("id", collection)));
    }

    assertEquals(
        List.of("Gamma", "Alpha", "Beta"),
        names(list(account, collection, 0)),
        "R19 — absent and 0 are identifier descending");
    assertEquals(List.of("Beta", "Alpha", "Gamma"), names(list(account, collection, 1)));
    assertEquals(List.of("Alpha", "Beta", "Gamma"), names(list(account, collection, 2)));
    assertEquals(List.of("Gamma", "Beta", "Alpha"), names(list(account, collection, 3)));
  }

  private JsonNode list(Account account, int collection, int sort) {
    return send(
        "GET",
        "/api/v1/links?collectionId=" + collection + "&sort=" + sort,
        account.token(),
        null);
  }

  private static List<String> names(JsonNode answer) {
    List<String> names = new java.util.ArrayList<>();
    answer.get("response").forEach(link -> names.add(link.get("name").asText()));
    return names;
  }

  @Test
  void changingTheAddressClearsEveryPreservedFormat() {
    Account account = register();
    int id = save(account, Map.of("url", "ftp://files.example.com/x")).get("response").get("id").asInt();

    JsonNode updated =
        send("PUT", "/api/v1/links/" + id, account.token(),
            Map.of("name", "Moved", "url", "https://moved.invalid/y"));
    assertEquals(200, status(updated), updated.toString());
    assertTrue(updated.get("response").get("preview").isNull(), "R43");
    assertTrue(updated.get("response").get("lastPreserved").isNull());
    assertTrue(updated.get("response").get("indexVersion").isNull(), "R43 — on every update");
  }

  @Test
  void anUpdateThatLeavesTheAddressOutIsRefusedTheSameWayAnUnparseableOneIs() {
    Account account = register();
    int id = save(account, Map.of("url", A)).get("response").get("id").asInt();
    JsonNode refusal = send("PUT", "/api/v1/links/" + id, account.token(), Map.of("name", "x"));
    assertEquals(401, status(refusal), "R43 — an absent address is a change to nothing");
    assertEquals("Invalid URL.", refusal.get("response").asText());
  }

  @Test
  void anUpdateToAnAddressThatWillNotParseIsRefused() {
    Account account = register();
    int id = save(account, Map.of("url", A)).get("response").get("id").asInt();
    JsonNode refusal =
        send("PUT", "/api/v1/links/" + id, account.token(),
            Map.of("name", "x", "url", "not a url"));
    assertEquals(401, status(refusal));
    assertEquals("Invalid URL.", refusal.get("response").asText());
  }

  @Test
  void duplicateTagNamesOnAnUpdateAreKeptOnce() {
    Account account = register();
    int id = save(account, Map.of("url", A)).get("response").get("id").asInt();
    JsonNode updated =
        send("PUT", "/api/v1/links/" + id, account.token(),
            Map.of("name", "x", "url", A,
                "tags", List.of(Map.of("name", "Same"), Map.of("name", "Same"))));
    assertEquals(1, updated.get("response").get("tags").size(), "R44");
  }

  @Test
  void pinningIsWhatThePinnedByFieldSays() {
    Account account = register();
    int id = save(account, Map.of("url", A)).get("response").get("id").asInt();

    JsonNode pinned =
        send("PUT", "/api/v1/links/" + id, account.token(),
            Map.of("name", "x", "url", A, "pinnedBy", List.of(Map.of("id", account.id()))));
    assertEquals(200, status(pinned));

    JsonNode onlyPinned = send("GET", "/api/v1/links?pinnedOnly=true", account.token(), null);
    assertTrue(
        containsId(onlyPinned, id), "R23 — a pinned link is what pinnedOnly answers: " + onlyPinned);

    send(
        "PUT",
        "/api/v1/links/" + id,
        account.token(),
        Map.of("name", "x", "url", A, "pinnedBy", List.of()));
    JsonNode afterUnpin = send("GET", "/api/v1/links?pinnedOnly=true", account.token(), null);
    assertFalse(containsId(afterUnpin, id));
  }

  private static boolean containsId(JsonNode listing, int id) {
    for (JsonNode link : listing.get("response")) if (link.get("id").asInt() == id) return true;
    return false;
  }

  @Test
  void aBulkUpdateReadsEachLinksOwnFieldsAndSkipsAnIdentifierNamingNothing() {
    Account account = register();
    int mine = save(account, Map.of("url", A)).get("response").get("id").asInt();

    // No address in the body: R47 lays the changes over each link's own fields, so R43's
    // refusal of an update with no address is not reached.
    JsonNode answered =
        send("PUT", "/api/v1/links", account.token(),
            Map.of(
                "links", List.of(Map.of("id", mine), Map.of("id", 999999)),
                "newData", Map.of("name", "Renamed")));
    assertEquals(200, status(answered), "R47 — an identifier naming nothing is skipped");
    assertEquals("All links updated successfully", answered.get("response").asText());
    assertEquals(
        "Renamed",
        send("GET", "/api/v1/links/" + mine, account.token(), null).get("response").get("name").asText());
  }

  @Test
  void aBulkUpdateThatOneLinkRefusesSaysSoAndKeepsWhatWorked() {
    Account owner = register();
    Account member = register();
    int mine = save(owner, Map.of("url", A)).get("response").get("id").asInt();
    int theirs = save(member, Map.of("url", B)).get("response").get("id").asInt();

    JsonNode partly =
        send("PUT", "/api/v1/links", owner.token(),
            Map.of(
                "links", List.of(Map.of("id", mine), Map.of("id", theirs)),
                "newData", Map.of("name", "Renamed")));
    assertEquals(400, status(partly), "R47");
    assertEquals("Some links failed to update", partly.get("response").asText());
    assertEquals(
        "Renamed",
        send("GET", "/api/v1/links/" + mine, owner.token(), null).get("response").get("name").asText(),
        "R47 — what succeeded is kept");
  }

  @Test
  void aBulkDeleteChecksEveryIdBeforeRemovingAny() {
    Account account = register();
    int mine = save(account, Map.of("url", A)).get("response").get("id").asInt();

    JsonNode refusal =
        send("DELETE", "/api/v1/links", account.token(),
            Map.of("linkIds", List.of(mine, 999999)));
    assertEquals(401, status(refusal), "R46");
    assertEquals(
        200,
        status(send("GET", "/api/v1/links/" + mine, account.token(), null)),
        "R46 — and nothing was removed");

    JsonNode empty = send("DELETE", "/api/v1/links", account.token(), Map.of("linkIds", List.of()));
    assertEquals(401, status(empty));
    assertEquals("Please choose valid links.", empty.get("response").asText());
  }

  @Test
  void askingForALinkThatDoesNotExistIsARefusalRatherThanANull() {
    Account account = register();
    JsonNode missing = send("GET", "/api/v1/links/999999", account.token(), null);
    assertEquals(401, status(missing));
    assertEquals("Collection is not accessible.", missing.get("response").asText());

    JsonNode zero = send("GET", "/api/v1/links/0", account.token(), null);
    assertEquals(401, status(zero));
    assertEquals("Please choose a valid link.", zero.get("response").asText());
  }

  @Test
  void archivingAgainAnswersTwoHundredEvenWhenItRefuses() {
    Account account = register();
    int noUrl = save(account, Map.of("type", "pdf", "name", "A file")).get("response").get("id").asInt();
    JsonNode refusal = send("PUT", "/api/v1/links/" + noUrl + "/archive", account.token(), null);
    assertEquals(200, status(refusal), "R54 — a 200 carrying the refusal");
    assertEquals("Invalid URL.", refusal.get("response").asText());

    int withUrl = save(account, Map.of("url", A)).get("response").get("id").asInt();
    JsonNode accepted = send("PUT", "/api/v1/links/" + withUrl + "/archive", account.token(), null);
    assertEquals(200, status(accepted));
    assertEquals("Link is being archived.", accepted.get("response").asText());
  }

  @Test
  void deletingArchivesNeedsSomethingItMayReach() {
    Account account = register();
    JsonNode nothing =
        send("DELETE", "/api/v1/links/archive", account.token(), Map.of("linkIds", List.of(999999)));
    assertEquals(401, status(nothing), "R55");
    assertEquals("Permission denied.", nothing.get("response").asText());

    int mine = save(account, Map.of("url", A)).get("response").get("id").asInt();
    JsonNode done =
        send("DELETE", "/api/v1/links/archive", account.token(), Map.of("linkIds", List.of(mine)));
    assertEquals(200, status(done));
    assertEquals("Success.", done.get("response").asText());
  }
}

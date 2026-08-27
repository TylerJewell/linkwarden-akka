package io.akka.linkwarden;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** SPEC-001 R5's public routes — what a collection marked public shows to a stranger. */
class PublicIntegrationTest extends SurfaceTestBase {

  /** A public collection with one tagged link in it. */
  private record Open(Account owner, int collection, int link) {}

  private Open anOpenCollection() {
    Account owner = register();
    int collection =
        send("POST", "/api/v1/collections", owner.token(), Map.of("name", "Open"))
            .get("response").get("id").asInt();
    send(
        "PUT",
        "/api/v1/collections/" + collection,
        owner.token(),
        Map.of("name", "Open", "isPublic", true));
    int link =
        send(
                "POST",
                "/api/v1/links",
                owner.token(),
                Map.of(
                    "url", "https://pu.invalid/a",
                    "name", "On show",
                    "collection", Map.of("id", collection),
                    "tags", List.of(Map.of("name", "Shown"))))
            .get("response")
            .get("id")
            .asInt();
    return new Open(owner, collection, link);
  }

  @Test
  void aPublicCollectionIsReadableWithoutATokenAndAPrivateOneIsNot() {
    Open open = anOpenCollection();
    JsonNode shown = send("GET", "/api/v1/public/collections/" + open.collection(), null, null);
    assertEquals(200, status(shown), shown.toString());
    assertEquals("Open", shown.get("response").get("name").asText());

    Account other = register();
    int closed =
        send("POST", "/api/v1/collections", other.token(), Map.of("name", "Closed"))
            .get("response").get("id").asInt();
    JsonNode hidden = send("GET", "/api/v1/public/collections/" + closed, null, null);
    assertEquals(400, status(hidden));
    assertEquals("Collection not found.", hidden.get("response").asText());
  }

  @Test
  void aLinkInAPrivateCollectionAnswersNothingRatherThanARefusal() {
    Account owner = register();
    int link =
        send("POST", "/api/v1/links", owner.token(), Map.of("url", "https://pu.invalid/hidden"))
            .get("response").get("id").asInt();
    JsonNode answer = send("GET", "/api/v1/public/links/" + link, null, null);
    assertEquals(200, status(answer));
    assertTrue(answer.get("response").isNull());
  }

  @Test
  void thePublicListingAndItsTagsAnswerUnderTheOtherEnvelope() {
    Open open = anOpenCollection();

    JsonNode links =
        send("GET", "/api/v1/public/collections/links?collectionId=" + open.collection(), null, null);
    assertEquals(200, status(links), links.toString());
    assertEquals(1, links.get("data").get("links").size());
    assertEquals(open.link(), links.get("data").get("links").get(0).get("id").asInt());
    // R64 — a public search carries no pin, because there is nobody whose pin it would be.
    assertFalse(links.get("data").get("links").get(0).has("pinnedBy"));

    JsonNode tags =
        send("GET", "/api/v1/public/collections/tags?collectionId=" + open.collection(), null, null);
    assertEquals(200, status(tags), tags.toString());
    assertEquals("Shown", tags.get("data").get("tags").get(0).get("name").asText());
  }

  @Test
  void thePublicListingNeedsACollectionToBeNamed() {
    JsonNode links = send("GET", "/api/v1/public/collections/links", null, null);
    assertEquals(400, status(links));
    assertEquals("Please choose a valid collection.", links.get("response").asText());
  }

  @Test
  void thePublicAccountDisclosesSevenFieldsAndNoMore() {
    Account account = register();
    JsonNode shown = send("GET", "/api/v1/public/users/" + account.username(), null, null);
    assertEquals(200, status(shown), shown.toString());
    JsonNode user = shown.get("response");
    assertEquals(7, user.size(), "the whole disclosure, and nothing else: " + user);
    assertTrue(user.has("username"));
    assertFalse(user.has("email"), "no address");
    assertFalse(user.has("password"));
  }
}

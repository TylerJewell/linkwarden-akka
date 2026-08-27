package io.akka.linkwarden;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * SPEC-001 R75–R77 — subscribing to a feed.
 *
 * <p>R75 puts the guard before everything else it checks, so an address that does not resolve is
 * refused before the rules under test are reached — which is why these use a name that does. The
 * feed behind it is not one, so the poll each subscription performs finds nothing and the
 * subscription is what is left to check. What a poll does with a feed it can read is checked
 * without a network by {@link RssPollingTest}.
 */
class RssIntegrationTest extends SurfaceTestBase {

  @Test
  void aFeedIsSubscribedToAndListedWithItsCollectionsName() {
    Account account = register();
    int collection =
        send("POST", "/api/v1/collections", account.token(), Map.of("name", "Feeds"))
            .get("response").get("id").asInt();

    JsonNode made =
        send(
            "POST",
            "/api/v1/rss",
            account.token(),
            Map.of("name", "A feed", "url", "https://example.com/feed.xml", "collectionId", collection));
    assertEquals(200, status(made), made.toString());

    JsonNode listed = send("GET", "/api/v1/rss", account.token(), null);
    assertEquals(1, listed.get("response").size());
    assertEquals("A feed", listed.get("response").get(0).get("name").asText());
    assertEquals("Feeds", listed.get("response").get(0).get("collection").get("name").asText());
  }

  @Test
  void aSecondFeedOfTheSameNameIsRefused() {
    Account account = register();
    send(
        "POST",
        "/api/v1/rss",
        account.token(),
        Map.of("name", "Same", "url", "https://example.com/one.xml"));
    JsonNode second =
        send(
            "POST",
            "/api/v1/rss",
            account.token(),
            Map.of("name", "Same", "url", "https://example.com/two.xml"));
    assertEquals(400, status(second), "R75");
    assertEquals("RSS Subscription with that name already exists", second.get("response").asText());
  }

  @Test
  void anAddressTheGuardRefusesIsRefusedHere() {
    Account account = register();
    JsonNode refusal =
        send(
            "POST",
            "/api/v1/rss",
            account.token(),
            Map.of("name", "Local", "url", "http://localhost/rss"));
    assertEquals(400, status(refusal), "R75");
    assertTrue(refusal.get("response").asText().length() > 0);
  }

  @Test
  void theTwentiethSubscriptionIsTheLastOneAllowed() {
    Account account = register();
    for (int i = 0; i < 20; i++) {
      JsonNode made =
          send(
              "POST",
              "/api/v1/rss",
              account.token(),
              Map.of("name", "Feed " + i, "url", "https://example.com/f" + i + ".xml"));
      assertEquals(200, status(made), "feed " + i + ": " + made);
    }
    JsonNode refusal =
        send(
            "POST",
            "/api/v1/rss",
            account.token(),
            Map.of("name", "One too many", "url", "https://example.com/f20.xml"));
    assertEquals(403, status(refusal), "R75");
    assertEquals(
        "You have reached the limit of 20 RSS subscriptions.", refusal.get("response").asText());
  }

  @Test
  void aSubscriptionIsDeletedByItsOwnerAndByNobodyElse() {
    Account owner = register();
    Account stranger = register();
    int id =
        send(
                "POST",
                "/api/v1/rss",
                owner.token(),
                Map.of("name", "Mine", "url", "https://example.com/mine.xml"))
            .get("response")
            .get("id")
            .asInt();

    assertEquals(404, status(send("DELETE", "/api/v1/rss/999999", owner.token(), null)), "R77");
    assertEquals(403, status(send("DELETE", "/api/v1/rss/" + id, stranger.token(), null)), "R77");

    JsonNode deleted = send("DELETE", "/api/v1/rss/" + id, owner.token(), null);
    assertEquals(200, status(deleted));
    assertEquals("RSS subscription deleted.", deleted.get("response").asText());
    assertEquals(0, send("GET", "/api/v1/rss", owner.token(), null).get("response").size());
  }
}

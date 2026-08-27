package io.akka.linkwarden;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** SPEC-001 R70 — marking a passage, and what marking the same one twice does. */
class HighlightIntegrationTest extends SurfaceTestBase {

  private int saveLink(Account account) {
    return send("POST", "/api/v1/links", account.token(), Map.of("url", "https://h.invalid/a"))
        .get("response").get("id").asInt();
  }

  @Test
  void aPassageIsMarkedAndReadBack() {
    Account account = register();
    int link = saveLink(account);

    JsonNode marked =
        send(
            "POST",
            "/api/v1/highlights",
            account.token(),
            Map.of(
                "linkId", link, "startOffset", 10, "endOffset", 40, "color", "yellow",
                "text", "the passage", "comment", "a note"));
    assertEquals(200, status(marked), marked.toString());
    assertEquals("yellow", marked.get("response").get("color").asText());

    JsonNode listed = send("GET", "/api/v1/links/" + link + "/highlights", account.token(), null);
    assertEquals(1, listed.get("response").size());
    assertEquals("the passage", listed.get("response").get(0).get("text").asText());
  }

  @Test
  void theSameOffsetsRecolourRatherThanMarkAgainAndTheTextIsNotTouched() {
    Account account = register();
    int link = saveLink(account);
    JsonNode first =
        send(
            "POST",
            "/api/v1/highlights",
            account.token(),
            Map.of("linkId", link, "startOffset", 5, "endOffset", 9, "color", "yellow",
                "text", "original"));
    int id = first.get("response").get("id").asInt();

    JsonNode second =
        send(
            "POST",
            "/api/v1/highlights",
            account.token(),
            Map.of("linkId", link, "startOffset", 5, "endOffset", 9, "color", "blue",
                "text", "different", "comment", "later"));
    assertEquals(id, second.get("response").get("id").asInt(), "R70 — the same mark, recoloured");
    assertEquals("blue", second.get("response").get("color").asText());
    assertEquals("later", second.get("response").get("comment").asText());
    assertEquals("original", second.get("response").get("text").asText(), "R70 — never the text");
  }

  @Test
  void aLinkInACollectionTheCallerCannotUpdateIsRefused() {
    Account owner = register();
    Account stranger = register();
    int link = saveLink(owner);

    JsonNode refusal =
        send(
            "POST",
            "/api/v1/highlights",
            stranger.token(),
            Map.of("linkId", link, "startOffset", 0, "endOffset", 1, "color", "yellow", "text", "x"));
    assertEquals(400, status(refusal), "R70");
    assertEquals("Collection not accessible", refusal.get("response").asText());
  }

  @Test
  void aMarkIsDeletedByWhoeverMadeItAndByNobodyElse() {
    Account account = register();
    Account stranger = register();
    int link = saveLink(account);
    int id =
        send(
                "POST",
                "/api/v1/highlights",
                account.token(),
                Map.of("linkId", link, "startOffset", 1, "endOffset", 2, "color", "green", "text", "x"))
            .get("response")
            .get("id")
            .asInt();

    JsonNode refused = send("DELETE", "/api/v1/highlights/" + id, stranger.token(), null);
    assertEquals(401, status(refused));

    JsonNode deleted = send("DELETE", "/api/v1/highlights/" + id, account.token(), null);
    assertEquals(200, status(deleted));
    assertEquals(id, deleted.get("response").asInt(), "the answer is what went");
    assertEquals(
        0,
        send("GET", "/api/v1/links/" + link + "/highlights", account.token(), null)
            .get("response").size());
  }
}

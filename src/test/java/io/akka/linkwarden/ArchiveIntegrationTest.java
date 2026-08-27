package io.akka.linkwarden;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** SPEC-001 R86–R90 — reading a preserved format, uploading one, and the avatar route. */
class ArchiveIntegrationTest extends SurfaceTestBase {

  private int saveLink(Account account) {
    return send("POST", "/api/v1/links", account.token(), Map.of("url", "https://ar.invalid/a"))
        .get("response")
        .get("id")
        .asInt();
  }

  private JsonNode upload(String path, String token, String mediaType, byte[] file) {
    return send(
        "POST",
        path,
        token,
        new io.akka.linkwarden.api.ArchiveEndpoint.Upload(mediaType, file, null));
  }

  @Test
  void aFormatThatCannotBeUploadedIsRefusedBeforeAnythingElse() {
    Account account = register();
    int link = saveLink(account);
    JsonNode refusal =
        upload("/api/v1/archives/" + link + "?format=3", account.token(), "application/json", new byte[] {1});
    assertEquals(400, status(refusal), "R89");
    assertEquals("This format cannot be uploaded.", refusal.get("response").asText());
  }

  @Test
  void aMediaTypeTheFormatDoesNotAdmitIsRefused() {
    Account account = register();
    int link = saveLink(account);
    JsonNode refusal =
        upload("/api/v1/archives/" + link + "?format=2", account.token(), "image/png", new byte[] {1, 2, 3});
    assertEquals(400, status(refusal), "R89");
    assertTrue(
        refusal.get("response").asText().startsWith("Sorry, we couldn't process your file."),
        refusal.toString());
  }

  @Test
  void aPdfUploadIsStoredAndItsPreviewIsMarkedUnavailable() {
    Account account = register();
    int link = saveLink(account);
    JsonNode uploaded =
        upload(
            "/api/v1/archives/" + link + "?format=2",
            account.token(),
            "application/pdf",
            "%PDF-1.4".getBytes(StandardCharsets.UTF_8));
    assertEquals(200, status(uploaded), uploaded.toString());
    assertTrue(uploaded.get("response").get("pdf").asText().endsWith(".pdf"), "R89");
    assertEquals("unavailable", uploaded.get("response").get("preview").asText(), "R89");
    assertTrue(uploaded.get("response").get("clientSide").asBoolean());
  }

  @Test
  void aJpegForALinkThatAlreadyHasAPngIsRefused() {
    Account account = register();
    int link = saveLink(account);
    upload(
        "/api/v1/archives/" + link + "?format=0",
        account.token(),
        "image/png",
        new byte[] {(byte) 0x89, 'P', 'N', 'G'});

    JsonNode refusal =
        upload(
            "/api/v1/archives/" + link + "?format=1",
            account.token(),
            "image/jpeg",
            new byte[] {(byte) 0xff, (byte) 0xd8});
    assertEquals(400, status(refusal), "R89");
    assertEquals("PNG or JPEG file already exists.", refusal.get("response").asText());
  }

  @Test
  void whatWasUploadedIsWhatComesBack() {
    Account account = register();
    int link = saveLink(account);
    byte[] bytes = "%PDF-1.4 a small document".getBytes(StandardCharsets.UTF_8);
    upload("/api/v1/archives/" + link + "?format=2", account.token(), "application/pdf", bytes);

    var response =
        httpClient
            .GET("/api/v1/archives/" + link + "?format=2")
            .addHeader("Authorization", "Bearer " + account.token())
            .parseResponseBody(read -> new String(read, StandardCharsets.UTF_8))
            .invoke();
    assertEquals(200, response.httpResponse().status().intValue(), "R86");
    assertEquals(new String(bytes, StandardCharsets.UTF_8), response.body());
  }

  @Test
  void aCollectionTheCallerCannotReachIsRefusedAndAFormatThatIsNotOneIsToo() {
    Account owner = register();
    Account stranger = register();
    int link = saveLink(owner);

    JsonNode refused =
        send("GET", "/api/v1/archives/" + link + "?format=2", stranger.token(), null);
    assertEquals(401, status(refused), "R86");
    assertEquals("You don't have access to this collection.", refused.get("response").asText());

    JsonNode badFormat =
        send("GET", "/api/v1/archives/" + link + "?format=9", owner.token(), null);
    assertEquals(401, status(badFormat), "R86");
    assertEquals("Invalid parameters.", badFormat.get("response").asText());
  }

  @Test
  void aPublicCollectionsArchiveIsReadableBySomebodySignedInAsNobody() {
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
                Map.of("url", "https://ar.invalid/public", "collection", Map.of("id", collection)))
            .get("response")
            .get("id")
            .asInt();

    JsonNode asNobody = send("GET", "/api/v1/archives/" + link + "?format=2", null, null);
    assertEquals(404, status(asNobody), "R86 — reachable, and the file is simply not there");
    assertEquals("File not found.", asNobody.get("__body").asText());
  }

  @Test
  void withoutAUserContentDomainTheSignedAddressRoutesSaySo() {
    Account account = register();
    JsonNode token = send("GET", "/api/v1/preserved/token?linkId=1&format=4", account.token(), null);
    assertEquals(400, status(token), "R87");
    assertEquals("User content domain is not configured.", token.get("response").asText());

    JsonNode view = send("GET", "/api/v1/preserved/view?token=x", account.token(), null);
    assertEquals(400, status(view), "R88");
  }

  @Test
  void theAvatarRouteRefusesAZeroAndAnAccountThatDoesNotExist() {
    Account account = register();
    JsonNode zero = send("GET", "/api/v1/avatar/0", account.token(), null);
    assertEquals(401, status(zero), "R90");
    assertEquals("Invalid parameters.", zero.get("__body").asText());

    JsonNode missing = send("GET", "/api/v1/avatar/999999", account.token(), null);
    assertEquals(400, status(missing), "R90");
    assertEquals("File inaccessible.", missing.get("__body").asText());
  }
}

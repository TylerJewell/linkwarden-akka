package io.akka.linkwarden;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * SPEC-001 R98–R99, RENDERING.md R1 — the subscription the interface reads its dashboard from.
 *
 * <p>Read with a plain HTTP client rather than through the test kit's, because the test kit's
 * waits for a whole response and a subscription does not have one: what is being checked is that
 * the connection stays open and carries messages, which is the whole difference between this and
 * a repeated request.
 */
class StreamIntegrationTest extends SurfaceTestBase {

  /** Reads events off an open subscription until {@code count} have arrived or the wait is up. */
  private List<String> read(String token, int count, Duration wait) throws Exception {
    HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    HttpRequest.Builder request =
        HttpRequest.newBuilder(
                URI.create(
                    "http://" + testKit.getHost() + ":" + testKit.getPort() + "/api/v2/dashboard/stream"))
            .header("Accept", "text/event-stream")
            .timeout(wait);
    if (token != null) request.header("Authorization", "Bearer " + token);

    HttpResponse<java.io.InputStream> response =
        client.send(request.GET().build(), HttpResponse.BodyHandlers.ofInputStream());
    assertEquals(200, response.statusCode(), "the subscription is served");
    assertTrue(
        response.headers().firstValue("content-type").orElse("").startsWith("text/event-stream"),
        "R1 — it is a stream, not a page: " + response.headers().map());

    List<String> events = new ArrayList<>();
    long deadline = System.nanoTime() + wait.toNanos();
    try (BufferedReader reader =
        new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
      String line;
      while (events.size() < count
          && System.nanoTime() < deadline
          && (line = reader.readLine()) != null) {
        if (line.startsWith("data:")) events.add(line.substring(5).trim());
      }
    } catch (java.io.IOException closed) {
      // Closing the reader is how the subscription is cancelled, and the read that was in
      // flight when that happened reports it; the events already collected are the answer.
    }
    return events;
  }

  @Test
  void theFirstMessageIsTheDashboardAsItStands() throws Exception {
    Account account = register();
    int collection =
        send("POST", "/api/v1/collections", account.token(), Map.of("name", "Watched"))
            .get("response").get("id").asInt();
    send(
        "POST",
        "/api/v1/links",
        account.token(),
        Map.of("url", "https://st.invalid/a", "name", "Already here",
            "collection", Map.of("id", collection)));

    List<String> events = read(account.token(), 1, Duration.ofSeconds(20));
    assertEquals(1, events.size(), "R99 — a client that connects is told the current state");
    JsonNode payload = MAPPER.readTree(events.get(0));
    assertTrue(payload.has("data"), payload.toString());
    assertTrue(payload.get("data").has("links"));
    boolean sawTheLink = false;
    for (JsonNode link : payload.get("data").get("links")) {
      if (link.get("name").asText().equals("Already here")) sawTheLink = true;
    }
    assertTrue(sawTheLink, "R99 — and the state is this caller's: " + payload);
  }

  @Test
  void aChangeTheCallerCanSeeArrivesWithoutBeingAskedFor() throws Exception {
    Account account = register();
    // The change is made from another thread while the subscription is open, which is the whole
    // shape of R1: nothing on the reading side asks for it.
    Thread.ofVirtual()
        .start(
            () -> {
              try {
                Thread.sleep(1500);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
              }
              send(
                  "POST",
                  "/api/v1/links",
                  account.token(),
                  Map.of("url", "https://st.invalid/b", "name", "Arrived later"));
            });

    List<String> events = read(account.token(), 2, Duration.ofSeconds(25));
    assertEquals(2, events.size(), "R99 — the snapshot, then the change: " + events);
    assertTrue(events.get(1).contains("Arrived later"), events.get(1));
  }

  @Test
  void aSubscriptionWithNobodyBehindItCarriesAnEmptyDashboardRatherThanARefusal() throws Exception {
    List<String> events = read(null, 1, Duration.ofSeconds(20));
    assertEquals(1, events.size());
    JsonNode payload = MAPPER.readTree(events.get(0));
    assertEquals(0, payload.get("data").get("links").size());
  }
}

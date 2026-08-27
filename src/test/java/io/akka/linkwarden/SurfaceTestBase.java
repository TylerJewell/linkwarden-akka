package io.akka.linkwarden;

import akka.javasdk.testkit.TestKitSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * What every test of the HTTP surface needs: an account, a token, and a way to send a request
 * with either.
 *
 * <p>Accounts are numbered per run rather than fixed, because the runtime is shared by every test
 * in the class and a username that collides answers the same refusal a genuine collision does —
 * which reads as a rule firing when it is really two tests standing on each other.
 */
public abstract class SurfaceTestBase extends TestKitSupport {

  protected static final ObjectMapper MAPPER = new ObjectMapper();

  private static final AtomicInteger COUNTER = new AtomicInteger();

  /**
   * The runtime answers before its routes are registered, and the first request of a class
   * then comes back 404 — which reads as a route that is not served rather than as one that
   * is not there yet. Waited for once per class, on the one route that needs nothing.
   */
  @org.junit.jupiter.api.BeforeAll
  public void theSurfaceIsServing() {
    org.awaitility.Awaitility.await()
        .atMost(60, java.util.concurrent.TimeUnit.SECONDS)
        .pollInterval(java.time.Duration.ofMillis(100))
        .untilAsserted(
            () -> org.junit.jupiter.api.Assertions.assertEquals(
                200, status(send("GET", "/api/v1/config", null, null))));
  }

  /** An account and the bearer token that acts as it. */
  protected record Account(int id, String username, String token) {}

  protected String unique(String prefix) {
    return prefix + "-" + COUNTER.incrementAndGet();
  }

  protected ObjectNode send(String method, String path, String token, Object body) {
    var request =
        switch (method) {
          case "GET" -> httpClient.GET(path);
          case "POST" -> httpClient.POST(path);
          case "PUT" -> httpClient.PUT(path);
          case "DELETE" -> httpClient.DELETE(path);
          default -> throw new IllegalArgumentException("no such method: " + method);
        };
    if (token != null) request = request.addHeader("Authorization", "Bearer " + token);
    if (body != null) request = request.withRequestBody(body);
    var response = request.parseResponseBody(bytes -> new String(bytes, StandardCharsets.UTF_8)).invoke();
    ObjectNode parsed;
    try {
      JsonNode read =
          response.body() == null || response.body().isEmpty()
              ? MAPPER.createObjectNode()
              : MAPPER.readTree(response.body());
      // Several routes answer a file or a plain sentence rather than an object. Those are kept
      // under a name of their own so that one reader serves every route.
      parsed = read.isObject() ? (ObjectNode) read : (ObjectNode) MAPPER.createObjectNode().set("__body", read);
    } catch (Exception e) {
      parsed = MAPPER.createObjectNode().put("__body", response.body());
    }
    parsed.put("__status", response.httpResponse().status().intValue());
    return parsed;
  }

  protected static int status(JsonNode answer) {
    return answer.get("__status").asInt();
  }

  /** Registers an account and signs in as it. */
  protected Account register() {
    String username = unique("person");
    JsonNode created =
        send("POST", "/api/v1/users", null,
            Map.of("name", "A Person", "username", username, "password", "a-good-password"));
    if (created.get("response") == null || created.get("response").get("id") == null) {
      throw new AssertionError("registering " + username + " answered " + created);
    }
    int id = created.get("response").get("id").asInt();
    return new Account(id, username, signIn(username, "a-good-password"));
  }

  protected String signIn(String username, String password) {
    JsonNode session =
        send("POST", "/api/v1/session", null,
            Map.of("username", username, "password", password, "sessionName", "a test"));
    return session.get("response").get("token").asText();
  }
}

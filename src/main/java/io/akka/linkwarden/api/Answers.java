package io.akka.linkwarden.api;

import akka.http.javadsl.model.ContentTypes;
import akka.http.javadsl.model.HttpEntities;
import akka.http.javadsl.model.HttpResponse;
import akka.http.javadsl.model.StatusCodes;
import akka.http.javadsl.model.headers.RawHeader;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.akka.linkwarden.domain.Validation;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The two envelopes the surface answers in, and nothing else. SPEC-001 R6–R7.
 *
 * <p>Most routes wrap their answer in {@code response}; six wrap it in {@code data} beside a
 * {@code success} flag and a {@code message}. Which route uses which is not a pattern — it is a
 * fact about the original that a caller's client already depends on, so the two are kept apart
 * here rather than regularised into one.
 */
public final class Answers {

  private static final ObjectMapper MAPPER =
      new ObjectMapper()
          .registerModule(new JavaTimeModule())
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

  private Answers() {}

  public static ObjectMapper mapper() {
    return MAPPER;
  }

  public static HttpResponse json(int status, Object body) {
    try {
      byte[] bytes = MAPPER.writeValueAsBytes(body);
      return HttpResponse.create()
          .withStatus(StatusCodes.get(status))
          .withEntity(HttpEntities.create(ContentTypes.APPLICATION_JSON, bytes));
    } catch (Exception e) {
      throw new IllegalStateException("could not write an answer", e);
    }
  }

  /** The ordinary envelope: {@code {"response": …}}. */
  public static HttpResponse wrapped(int status, Object response) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("response", response);
    return json(status, body);
  }

  /** The envelope the six listing routes use instead. */
  public static HttpResponse enveloped(int status, Object data, boolean success, String message) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("data", data);
    body.put("success", success);
    body.put("message", message);
    return json(status, body);
  }

  public static HttpResponse issue(Validation.Issue issue) {
    return wrapped(400, issue.rendered());
  }

  public static HttpResponse text(int status, String contentType, byte[] bytes, List<RawHeader> headers) {
    HttpResponse response =
        HttpResponse.create()
            .withStatus(StatusCodes.get(status))
            .withEntity(
                HttpEntities.create(
                    akka.http.javadsl.model.ContentTypes.parse(contentType), bytes));
    for (RawHeader header : headers) response = response.addHeader(header);
    return response;
  }

  public static HttpResponse plain(int status, String body) {
    return HttpResponse.create()
        .withStatus(StatusCodes.get(status))
        .withEntity(
            HttpEntities.create(
                ContentTypes.TEXT_PLAIN_UTF8, body.getBytes(StandardCharsets.UTF_8)));
  }

  /** SPEC-001 R8 — the sentence every state-changing route answers in demonstration mode. */
  public static HttpResponse demoRefusal() {
    return wrapped(
        400, "This action is disabled because this is a read-only demo of Linkwarden.");
  }

  public static HttpResponse methodNotAllowed() {
    return wrapped(405, "Method not allowed");
  }
}

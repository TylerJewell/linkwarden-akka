package io.akka.linkwarden.api;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;

/**
 * Reading a request body as a tree.
 *
 * <p>The original's schemas distinguish a field that is absent from one that is present and null,
 * and several rules read that difference, so every reader here answers {@code null} for both and
 * {@link #has} is what a rule asks when the two differ.
 */
public final class Bodies {

  private Bodies() {}

  public static boolean has(JsonNode body, String field) {
    return body != null && body.has(field) && !body.get(field).isNull();
  }

  public static String text(JsonNode body, String field) {
    JsonNode node = body == null ? null : body.get(field);
    return node == null || node.isNull() ? null : node.asText();
  }

  public static Boolean flag(JsonNode body, String field) {
    JsonNode node = body == null ? null : body.get(field);
    return node == null || node.isNull() ? null : node.asBoolean();
  }

  public static boolean isOn(JsonNode body, String field) {
    return Boolean.TRUE.equals(flag(body, field));
  }

  public static Integer number(JsonNode body, String field) {
    JsonNode node = body == null ? null : body.get(field);
    return node == null || node.isNull() || !node.isNumber() ? null : node.asInt();
  }

  public static JsonNode child(JsonNode body, String field) {
    JsonNode node = body == null ? null : body.get(field);
    return node == null || node.isNull() ? null : node;
  }

  public static List<JsonNode> array(JsonNode body, String field) {
    JsonNode node = body == null ? null : body.get(field);
    if (node == null || !node.isArray()) return List.of();
    List<JsonNode> out = new ArrayList<>();
    node.forEach(out::add);
    return out;
  }

  public static List<Integer> integers(JsonNode body, String field) {
    JsonNode node = body == null ? null : body.get(field);
    if (node == null || !node.isArray()) return null;
    List<Integer> out = new ArrayList<>();
    node.forEach(item -> out.add(item.asInt()));
    return out;
  }

  public static List<String> strings(JsonNode body, String field) {
    JsonNode node = body == null ? null : body.get(field);
    if (node == null || !node.isArray()) return null;
    List<String> out = new ArrayList<>();
    node.forEach(item -> out.add(item.asText()));
    return out;
  }
}

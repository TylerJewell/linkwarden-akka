package io.akka.linkwarden.domain;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The query language a search box accepts. SPEC-001 R60–R65.
 *
 * <p>Splitting, reading a token as a filter, and turning the filters into the search engine's own
 * expression are three separate steps because the fallback path uses the first two and not the
 * third.
 */
public final class SearchQuery {

  public record Token(String field, String value, boolean negative) {}

  public static final String GENERAL = "general";

  public static final List<String> FIELDS =
      List.of(
          "url",
          "name",
          "description",
          "type",
          "collection",
          "pinned",
          "public",
          "before",
          "after",
          "tag");

  private SearchQuery() {}

  /**
   * Splits on unquoted whitespace, with either quote character toggling quoting and a backslash
   * escaping whatever follows it.
   */
  public static List<String> split(String query) {
    List<String> parts = new ArrayList<>();
    parts.add("");
    boolean quoted = false;
    for (int i = 0; i < query.length(); i++) {
      char c = query.charAt(i);
      if (c == '\\' && i + 1 < query.length()) {
        i++;
        parts.set(parts.size() - 1, parts.get(parts.size() - 1) + query.charAt(i));
      } else if (c == '"' || c == '\'') {
        quoted = !quoted;
      } else if (!quoted && c == ' ') {
        parts.add("");
      } else {
        parts.set(parts.size() - 1, parts.get(parts.size() - 1) + c);
      }
    }
    return parts;
  }

  public static List<Token> parse(String query, int filterLimit) {
    List<Token> tokens = new ArrayList<>();
    for (String raw : split(query)) {
      String token = raw;
      boolean negative = false;
      if (token.startsWith("!") && token.length() > 1) {
        String afterNegation = token.substring(1);
        if (FIELDS.stream().anyMatch(f -> afterNegation.startsWith(f + ":"))) {
          negative = true;
          token = afterNegation;
        }
      }
      String matched = null;
      for (String field : FIELDS) {
        String prefix = field + ":";
        if (token.startsWith(prefix) && token.length() > prefix.length()) {
          tokens.add(new Token(field, token.substring(prefix.length()), negative));
          matched = field;
          break;
        }
      }
      if (matched == null) tokens.add(new Token(GENERAL, token, negative));
    }
    if (filterLimit > 0) {
      List<Token> general = tokens.stream().filter(t -> t.field().equals(GENERAL)).toList();
      List<Token> others = tokens.stream().filter(t -> !t.field().equals(GENERAL)).toList();
      List<Token> capped = new ArrayList<>(general);
      capped.addAll(others.subList(0, Math.min(filterLimit, others.size())));
      return capped;
    }
    return tokens;
  }

  /** The free-text part: every general token, joined by a space. */
  public static String freeText(List<Token> tokens) {
    return String.join(
        " ", tokens.stream().filter(t -> t.field().equals(GENERAL)).map(Token::value).toList());
  }

  public static String escape(String value) {
    return value.replace("\\", "\\\\").replace("\"", "\\\"");
  }

  /**
   * The filter expressions, in the search engine's own syntax.
   *
   * <p>The first entry is always the visibility filter, so a query that names no field still
   * cannot reach somebody else's links.
   */
  public static List<String> filters(List<Token> tokens, Integer userId, boolean publicOnly) {
    List<String> filters = new ArrayList<>();
    if (publicOnly) {
      filters.add("collectionIsPublic = true");
    } else {
      filters.add(
          "(collectionOwnerId = " + userId + ") OR (collectionMemberIds = " + userId + ")");
    }
    for (Token token : tokens) {
      String value = token.value();
      boolean negative = token.negative();
      switch (token.field()) {
        case "url" -> filters.add(equality("url", value, negative));
        case "name" -> filters.add(equality("name", value, negative));
        case "description" -> filters.add(equality("description", value, negative));
        case "type" -> filters.add(equality("type", value, negative));
        case "collection" -> filters.add(equality("collectionName", value, negative));
        case "tag" -> filters.add(equality("tags", value, negative));
        case "pinned" -> {
          if ("true".equals(value)) {
            filters.add(negative ? "NOT pinnedBy = " + userId : "pinnedBy = " + userId);
          } else if ("false".equals(value)) {
            filters.add(negative ? "pinnedBy = " + userId : "NOT pinnedBy = " + userId);
          }
        }
        case "public" -> {
          if ("true".equals(value)) {
            filters.add(
                negative ? "NOT collectionIsPublic = true" : "collectionIsPublic = true");
          }
        }
        case "before" ->
            parseSeconds(value)
                .ifPresent(
                    seconds ->
                        filters.add(
                            negative
                                ? "creationTimestamp >= " + seconds
                                : "creationTimestamp < " + seconds));
        case "after" ->
            parseSeconds(value)
                .ifPresent(
                    seconds ->
                        filters.add(
                            negative
                                ? "creationTimestamp <= " + seconds
                                : "creationTimestamp > " + seconds));
        default -> {
          // general text is the query, not a filter
        }
      }
    }
    return filters;
  }

  private static String equality(String field, String value, boolean negative) {
    String clause = field + " = \"" + escape(value) + "\"";
    return negative ? "NOT " + clause : clause;
  }

  /**
   * Whole seconds since the epoch, or nothing when the value is not a date.
   *
   * <p>A date the parser cannot read produces no filter at all rather than a filter that matches
   * nothing, which is what makes {@code before:tomorrow} widen a search instead of emptying it.
   */
  public static Optional<Long> parseSeconds(String value) {
    for (java.time.format.DateTimeFormatter formatter :
        List.of(
            java.time.format.DateTimeFormatter.ISO_INSTANT,
            java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME)) {
      try {
        return Optional.of(Instant.from(formatter.parse(value)).getEpochSecond());
      } catch (java.time.DateTimeException e) {
        // try the next shape
      }
    }
    try {
      return Optional.of(
          java.time.LocalDate.parse(value).atStartOfDay(java.time.ZoneOffset.UTC).toEpochSecond());
    } catch (DateTimeParseException e) {
      return Optional.empty();
    }
  }
}

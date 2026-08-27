package io.akka.linkwarden.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.parser.Parser;

/**
 * The five formats an account can be imported from, read into one shape. SPEC-001 R78–R81.
 *
 * <p>Each importer is a pure function from the file's bytes to a plan: which collections to make
 * and what to put in them. Nothing here touches a store, so the truncations and the skips — which
 * is most of what these rules are — are testable against the file alone.
 */
public final class Importers {

  /** A link an import wants created, with the collection it belongs to named by its index. */
  public record PlannedLink(
      int collectionIndex,
      String url,
      String name,
      String description,
      String textContent,
      String image,
      List<String> tags,
      Instant importDate,
      boolean pinned) {}

  /** A collection an import wants created; {@code parentIndex} is -1 at the top level. */
  public record PlannedCollection(String name, int parentIndex) {}

  public record Plan(List<PlannedCollection> collections, List<PlannedLink> links) {

    public int linkCount() {
      return links.size();
    }
  }

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private Importers() {}

  static String cut(String value, int length) {
    if (value == null) return null;
    String trimmed = value.trim();
    return trimmed.length() > length ? trimmed.substring(0, length) : trimmed;
  }

  // ------------------------------------------------------------------
  // Netscape bookmark file
  // ------------------------------------------------------------------

  /**
   * A browser's bookmark export.
   *
   * <p>The structure is a {@code DL} of {@code DT}s: a {@code DT} holding an {@code H3} names a
   * collection whose contents are the {@code DL} that follows, and a {@code DT} holding an {@code
   * A} is a link. A {@code DD} that follows a {@code DT} describes the link inside it. A link that
   * is not inside any collection goes to one called {@code Imports}, created on first need.
   */
  public static Plan fromHtml(String raw) {
    Document document = Jsoup.parse(raw, "", Parser.htmlParser());
    List<PlannedCollection> collections = new ArrayList<>();
    List<PlannedLink> links = new ArrayList<>();
    int[] importsIndex = {-1};

    for (Element list : document.select("dl")) {
      if (list.parents().stream().anyMatch(p -> p.tagName().equals("dl"))) continue;
      walk(list, -1, collections, links, importsIndex);
    }
    return new Plan(collections, links);
  }

  private static void walk(
      Element list,
      int parentIndex,
      List<PlannedCollection> collections,
      List<PlannedLink> links,
      int[] importsIndex) {

    List<Node> children = list.childNodes();
    for (int i = 0; i < children.size(); i++) {
      if (!(children.get(i) instanceof Element item)) continue;
      if (!item.tagName().equals("dt")) continue;

      Element heading = item.selectFirst("> h3");
      if (heading != null) {
        String name = heading.text();
        int index = findOrAdd(collections, name.isEmpty() ? "Untitled Collection" : name, parentIndex);
        Element nested = item.selectFirst("> dl");
        if (nested == null) nested = nextSibling(children, i, "dl");
        if (nested != null) walk(nested, index, collections, links, importsIndex);
        continue;
      }

      Element anchor = item.selectFirst("> a");
      if (anchor == null) continue;

      String url = org.jsoup.parser.Parser.unescapeEntities(anchor.attr("href"), false);
      if (!Validation.isParseableUrl(url)) continue;

      String description = describedBy(anchor);
      String tagAttribute = anchor.attr("tags");
      List<String> tags =
          tagAttribute.isEmpty()
              ? List.of()
              : java.util.Arrays.stream(tagAttribute.split(","))
                  .map(t -> Parser.unescapeEntities(t, false))
                  .map(t -> cut(t, 49))
                  .filter(t -> !t.isEmpty())
                  .toList();

      Instant importDate = null;
      String addDate = anchor.attr("add_date");
      if (!addDate.isEmpty()) {
        try {
          importDate = Instant.ofEpochSecond(Long.parseLong(addDate));
        } catch (NumberFormatException e) {
          importDate = null;
        }
      }

      int collectionIndex = parentIndex;
      if (collectionIndex < 0) {
        if (importsIndex[0] < 0) importsIndex[0] = findOrAdd(collections, "Imports", -1);
        collectionIndex = importsIndex[0];
      }

      links.add(
          new PlannedLink(
              collectionIndex,
              cut(url, 2047),
              cut(anchor.text(), 254),
              cut(description, 254),
              null,
              null,
              tags,
              importDate,
              false));
    }
  }

  /**
   * The {@code DD} describing a bookmark, which is a child of the anchor and nothing else.
   *
   * <p>A bookmark file writes the description as a {@code DD} following the {@code DT}, and a
   * parser reading the markup as written puts it there rather than inside the anchor. Nothing
   * reads it from there, so a file in that ordinary shape imports with no description at all.
   */
  private static String describedBy(Element anchor) {
    Element inside = anchor.selectFirst("> dd");
    return inside == null ? "" : firstText(inside);
  }

  private static String firstText(Element element) {
    for (Node node : element.childNodes()) {
      if (node instanceof TextNode text && !text.text().isBlank()) return text.text();
    }
    return "";
  }

  private static Element nextSibling(List<Node> siblings, int index, String tag) {
    for (int i = index + 1; i < siblings.size(); i++) {
      if (siblings.get(i) instanceof Element candidate) {
        if (candidate.tagName().equals(tag)) return candidate;
        if (candidate.tagName().equals("dt")) return null;
      }
    }
    return null;
  }

  private static int findOrAdd(List<PlannedCollection> collections, String name, int parentIndex) {
    String trimmed = cut(name, 254);
    for (int i = 0; i < collections.size(); i++) {
      PlannedCollection existing = collections.get(i);
      if (existing.name().equals(trimmed) && existing.parentIndex() == parentIndex) return i;
    }
    collections.add(new PlannedCollection(trimmed, parentIndex));
    return collections.size() - 1;
  }

  // ------------------------------------------------------------------
  // Linkwarden's own backup
  // ------------------------------------------------------------------

  public static Plan fromLinkwarden(String raw) throws Exception {
    JsonNode root = MAPPER.readTree(raw);
    List<PlannedCollection> collections = new ArrayList<>();
    List<PlannedLink> links = new ArrayList<>();

    List<String> pinned = new ArrayList<>();
    for (JsonNode link : root.path("pinnedLinks")) {
      if (link.hasNonNull("url")) pinned.add(link.get("url").asText());
    }

    for (JsonNode collection : root.path("collections")) {
      collections.add(new PlannedCollection(cut(collection.path("name").asText(""), 254), -1));
      int index = collections.size() - 1;
      for (JsonNode link : collection.path("links")) {
        String url = link.path("url").asText(null);
        if (url != null && !Validation.isParseableUrl(url)) continue;
        List<String> tags = new ArrayList<>();
        for (JsonNode tag : link.path("tags")) tags.add(cut(tag.path("name").asText(""), 49));
        links.add(
            new PlannedLink(
                index,
                cut(url, 2047),
                cut(link.path("name").asText(""), 254),
                cut(link.path("description").asText(""), 254),
                null,
                null,
                tags,
                parseInstant(
                    link.hasNonNull("importDate")
                        ? link.get("importDate").asText()
                        : link.path("createdAt").asText(null)),
                url != null && pinned.contains(url)));
      }
    }
    return new Plan(collections, links);
  }

  // ------------------------------------------------------------------
  // Wallabag, Omnivore, Pocket
  // ------------------------------------------------------------------

  public static Plan fromWallabag(String raw) throws Exception {
    JsonNode root = MAPPER.readTree(raw);
    List<PlannedLink> links = new ArrayList<>();
    for (JsonNode item : root) {
      String url = item.path("url").asText(null);
      if (url == null || url.isEmpty() || !Validation.isParseableUrl(url)) continue;
      List<String> tags = new ArrayList<>();
      for (JsonNode tag : item.path("tags")) tags.add(cut(tag.asText(""), 49));
      links.add(
          new PlannedLink(
              0,
              cut(url, 2047),
              cut(item.path("title").asText(""), 254),
              "",
              cut(item.path("content").asText(""), 2047),
              null,
              tags,
              parseInstant(item.path("created_at").asText(null)),
              item.path("is_starred").asInt(0) != 0));
    }
    return new Plan(List.of(new PlannedCollection("Imports", -1)), links);
  }

  public static Plan fromOmnivore(String raw) throws Exception {
    JsonNode root = MAPPER.readTree(raw);
    List<PlannedLink> links = new ArrayList<>();
    for (JsonNode item : root) {
      String url = item.path("url").asText(null);
      if (url == null || url.isEmpty() || !Validation.isParseableUrl(url)) continue;
      List<String> tags = new ArrayList<>();
      for (JsonNode label : item.path("labels")) tags.add(cut(label.asText(""), 49));
      links.add(
          new PlannedLink(
              0,
              cut(url, 2047),
              cut(item.path("title").asText(""), 254),
              cut(item.path("description").asText(""), 2047),
              null,
              item.path("thumbnail").asText(""),
              tags,
              parseInstant(item.path("savedAt").asText(null)),
              false));
    }
    return new Plan(List.of(new PlannedCollection("Omnivore Imports", -1)), links);
  }

  public static Plan fromPocket(String raw) {
    List<List<String>> rows = Csv.parse(raw);
    List<PlannedLink> links = new ArrayList<>();
    if (rows.isEmpty()) return new Plan(List.of(new PlannedCollection("Imports", -1)), links);

    List<String> header = rows.get(0);
    int urlColumn = header.indexOf("url");
    int titleColumn = header.indexOf("title");
    int addedColumn = header.indexOf("time_added");
    int tagsColumn = header.indexOf("tags");

    for (List<String> row : rows.subList(1, rows.size())) {
      String url = urlColumn >= 0 && urlColumn < row.size() ? row.get(urlColumn) : null;
      if (url == null || url.isEmpty() || !Validation.isParseableUrl(url)) continue;
      String tagField = tagsColumn >= 0 && tagsColumn < row.size() ? row.get(tagsColumn) : "";
      List<String> tags =
          tagField.isEmpty()
              ? List.of()
              : java.util.Arrays.stream(tagField.split("\\|")).map(t -> cut(t, 50)).toList();
      Instant added = null;
      String addedField = addedColumn >= 0 && addedColumn < row.size() ? row.get(addedColumn) : "";
      if (!addedField.isEmpty()) {
        try {
          added = Instant.ofEpochSecond(Long.parseLong(addedField.trim()));
        } catch (NumberFormatException e) {
          added = null;
        }
      }
      links.add(
          new PlannedLink(
              0,
              cut(url, 2047),
              cut(titleColumn >= 0 && titleColumn < row.size() ? row.get(titleColumn) : "", 254),
              "",
              null,
              null,
              tags,
              added,
              false));
    }
    return new Plan(List.of(new PlannedCollection("Imports", -1)), links);
  }

  static Instant parseInstant(String value) {
    if (value == null || value.isEmpty()) return null;
    try {
      return Instant.parse(value);
    } catch (Exception e) {
      try {
        return java.time.OffsetDateTime.parse(value).toInstant();
      } catch (Exception ignored) {
        return null;
      }
    }
  }
}

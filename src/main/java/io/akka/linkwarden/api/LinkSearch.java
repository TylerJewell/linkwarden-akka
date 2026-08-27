package io.akka.linkwarden.api;

import io.akka.linkwarden.application.Data;
import io.akka.linkwarden.application.LinksView;
import io.akka.linkwarden.domain.Config;
import io.akka.linkwarden.domain.Permissions;
import io.akka.linkwarden.domain.Records;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The search that runs when there is no search engine. SPEC-001 R65.
 *
 * <p>With one, the query language of R60–R63 becomes filters an engine reads. Without one, the
 * same query is matched against four columns of the rows themselves, and the combining rule of
 * R41 is what decides whether the other filters narrow the answer or widen it. This instance ships
 * without an engine, so this is the path every search takes; it is shared between the signed-in
 * search and the public one, which differ only in the visibility filter.
 */
final class LinkSearch {

  /** What a caller asked for, in the shape both routes read it in. */
  /**
   * @param omitText true for the search, which leaves the page's own text out of a link; the
   *     listing route keeps it
   */
  record Request(
      Integer cursor,
      Integer collectionId,
      Integer tagId,
      boolean pinnedOnly,
      String text,
      int sort,
      boolean omitText) {}

  private LinkSearch() {}

  /**
   * SPEC-001 R64–R65 — one page of links, and the identifier the next page resumes from.
   *
   * @param viewer the caller, or null for a public search, which sees public collections only
   */
  static Map<String, Object> run(Data data, Config config, Records.User viewer, Request request) {
    Integer viewerId = viewer == null ? null : viewer.id();
    List<LinksView.LinkRow> rows = new ArrayList<>();
    // The set to look in is decided before anything is filtered: a signed-in caller's is every
    // collection they can reach, and a public search's is the one public collection it named.
    for (LinksView.LinkRow row : candidates(data, viewer, request)) {
      if (!matches(data, row, request, viewerId)) continue;
      rows.add(row);
    }

    sort(rows, request.sort());
    if (request.cursor() != null) {
      int at = -1;
      for (int i = 0; i < rows.size(); i++) if (rows.get(i).id() == request.cursor()) at = i;
      if (at >= 0) rows = new ArrayList<>(rows.subList(at + 1, rows.size()));
    }
    int take = config.paginationTakeCount();
    boolean full = rows.size() >= take;
    if (full) rows = new ArrayList<>(rows.subList(0, take));

    List<Map<String, Object>> links = new ArrayList<>();
    for (LinksView.LinkRow row : rows) {
      Records.Link link = data.link(row.id()).orElse(null);
      if (link == null) continue;
      List<Integer> pins =
          viewerId == null
              ? null
              : (link.pinnedBy().contains(viewerId) ? List.of(viewerId) : List.of());
      links.add(
          Shapes.link(
              link,
              data.collection(link.collectionId()).orElse(null),
              data.tagsOf(link),
              pins,
              request.omitText()));
    }

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("links", links);
    body.put("nextCursor", full && !rows.isEmpty() ? rows.get(rows.size() - 1).id() : null);
    return body;
  }

  private static List<LinksView.LinkRow> candidates(
      Data data, Records.User viewer, Request request) {
    if (viewer != null) return data.reachableLinkRows(viewer.id());
    if (request.collectionId() == null) return List.of();
    Optional<Records.Collection> collection = data.collection(request.collectionId());
    if (collection.isEmpty() || !collection.get().isPublic()) return List.of();
    return data.linkRowsIn(request.collectionId());
  }

  /**
   * SPEC-001 R41 — the collection narrows on its own; a tag stands beside everything else; and
   * whether the pin and the text narrow together or widen depends on there being a text.
   *
   * <p>The shape is not symmetrical and is worth reading twice. A tag is offered as an
   * alternative to the whole of the rest, so a link carrying the tag is answered whatever else
   * was asked. Without a text the pin narrows, which is what makes a pinned-only listing a
   * listing of pinned links. With one, the pin and the four columns are alternatives to each
   * other, so a pinned-only search answers links that are not pinned but match the words.
   */
  private static boolean matches(
      Data data, LinksView.LinkRow row, Request request, Integer viewerId) {
    if (request.collectionId() != null && row.collectionId() != request.collectionId()) {
      return false;
    }

    // The second branch beside the tag, which is null when it names no condition at all: with
    // no text and no pin asked for, it constrains nothing, and a branch that constrains nothing
    // is dropped rather than made true. That is the difference between a tag search, which
    // answers the tag's links, and a bare listing, which answers every link.
    Boolean beside;
    boolean pinned = viewerId != null && row.pinnedBy().contains(viewerId);
    if (request.text() == null || request.text().isEmpty()) {
      beside = request.pinnedOnly() ? pinned : null;
    } else {
      beside = (request.pinnedOnly() && pinned) || matchesText(data, row, request.text());
    }

    if (request.tagId() != null) {
      boolean tagged = row.tagIds().contains(request.tagId());
      return beside == null ? tagged : tagged || beside;
    }
    return beside == null || beside;
  }

  private static boolean matchesText(Data data, LinksView.LinkRow row, String text) {
    String needle = text.toLowerCase();
    if (contains(row.name(), needle)) return true;
    if (row.url().isPresent() && contains(row.url().get(), needle)) return true;
    if (contains(row.description(), needle)) return true;
    for (int tagId : row.tagIds()) {
      Optional<Records.Tag> tag = data.tag(tagId);
      if (tag.isPresent() && contains(tag.get().name(), needle)) return true;
    }
    return false;
  }

  private static boolean contains(String value, String lowerNeedle) {
    return value != null && value.toLowerCase().contains(lowerNeedle);
  }

  private static void sort(List<LinksView.LinkRow> rows, int sort) {
    switch (sort) {
      case 1 -> rows.sort(Comparator.comparingInt(LinksView.LinkRow::id));
      case 2 -> rows.sort(
          Comparator.comparing(LinksView.LinkRow::name, String.CASE_INSENSITIVE_ORDER)
              .thenComparingInt(LinksView.LinkRow::id));
      case 3 -> rows.sort(
          Comparator.comparing(LinksView.LinkRow::name, String.CASE_INSENSITIVE_ORDER)
              .reversed()
              .thenComparingInt(LinksView.LinkRow::id));
      default -> rows.sort(Comparator.comparingInt(LinksView.LinkRow::id).reversed());
    }
  }
}

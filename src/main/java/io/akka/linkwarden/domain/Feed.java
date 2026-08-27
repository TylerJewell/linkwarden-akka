package io.akka.linkwarden.domain;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;

/**
 * An RSS or Atom document, as the polling rule reads it. SPEC-001 R76.
 *
 * <p>Only four things are read out of a feed: its own build date, and each item's title, link and
 * publication date. Everything else a feed may carry is ignored, which is what the original's
 * parser does too — the decision that matters is which items are newer than what was last stored.
 */
public final class Feed {

  /** One entry, with the publication instant absent when the feed did not give a readable one. */
  public record Item(String title, String link, Instant published) {}

  /** {@code lastBuildDate} when the feed states one; otherwise absent. */
  public record Parsed(Instant lastBuildDate, List<Item> items) {

    /**
     * SPEC-001 R76 — the date a poll compares against: the feed's own, or the latest item's.
     *
     * <p>The epoch stands where a feed states neither, which is the value the original's reduction
     * starts from and is older than any stored date, so such a feed is never processed twice.
     */
    public Instant effectiveDate() {
      if (lastBuildDate != null) return lastBuildDate;
      Instant latest = Instant.EPOCH;
      for (Item item : items) {
        if (item.published() != null && item.published().isAfter(latest)) latest = item.published();
      }
      return latest;
    }
  }

  private Feed() {}

  public static Parsed parse(String xml) {
    if (xml == null || xml.isBlank()) return new Parsed(null, List.of());
    Document document = Jsoup.parse(xml, "", Parser.xmlParser());

    Instant lastBuildDate = null;
    Element stated = document.selectFirst("channel > lastBuildDate");
    if (stated == null) stated = document.selectFirst("feed > updated");
    if (stated != null) lastBuildDate = instant(stated.text());

    List<Item> items = new ArrayList<>();
    for (Element element : document.select("item, entry")) {
      Element title = element.selectFirst("title");
      String link = linkOf(element);
      Element date = element.selectFirst("pubDate");
      if (date == null) date = element.selectFirst("published");
      if (date == null) date = element.selectFirst("updated");
      items.add(
          new Item(
              title == null ? null : title.text(),
              link,
              date == null ? null : instant(date.text())));
    }
    return new Parsed(lastBuildDate, items);
  }

  /** Atom puts the address on an attribute; RSS puts it in the element's text. */
  private static String linkOf(Element element) {
    Element link = element.selectFirst("link");
    if (link == null) return null;
    String href = link.attr("href");
    if (href != null && !href.isEmpty()) return href;
    String text = link.text();
    return text == null || text.isEmpty() ? null : text;
  }

  /**
   * A date in either of the two spellings feeds use, and nothing when it is in neither.
   *
   * <p>An unreadable date is absent rather than the epoch: absent means the item is never newer
   * than the stored date, so an item nobody can date is not published again on every poll.
   */
  public static Instant instant(String raw) {
    if (raw == null || raw.isBlank()) return null;
    String text = raw.trim();
    try {
      return ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
    } catch (RuntimeException ignored) {
      // not RFC 1123; try the ISO spelling below
    }
    try {
      return ZonedDateTime.parse(text, DateTimeFormatter.ISO_DATE_TIME).toInstant();
    } catch (RuntimeException ignored) {
      return null;
    }
  }
}

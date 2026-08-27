package io.akka.linkwarden.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/** SPEC-001 R76 — what a poll reads out of a feed, and what it compares. */
class FeedTest {

  private static final String RSS =
      """
      <rss version="2.0"><channel>
        <title>A feed</title>
        <lastBuildDate>Tue, 25 Aug 2026 10:00:00 GMT</lastBuildDate>
        <item>
          <title>First</title>
          <link>https://example.com/first</link>
          <pubDate>Mon, 24 Aug 2026 09:00:00 GMT</pubDate>
        </item>
        <item>
          <title>Second</title>
          <link>https://example.com/second</link>
          <pubDate>Tue, 25 Aug 2026 09:00:00 GMT</pubDate>
        </item>
      </channel></rss>
      """;

  private static final String ATOM =
      """
      <feed xmlns="http://www.w3.org/2005/Atom">
        <title>An atom feed</title>
        <entry>
          <title>Only</title>
          <link href="https://example.com/only"/>
          <published>2026-08-24T09:00:00Z</published>
        </entry>
      </feed>
      """;

  @Test
  void anRssFeedGivesItsBuildDateAndEveryItem() {
    Feed.Parsed parsed = Feed.parse(RSS);
    assertEquals(Instant.parse("2026-08-25T10:00:00Z"), parsed.lastBuildDate());
    assertEquals(2, parsed.items().size());
    assertEquals("First", parsed.items().get(0).title());
    assertEquals("https://example.com/first", parsed.items().get(0).link());
    assertEquals(Instant.parse("2026-08-24T09:00:00Z"), parsed.items().get(0).published());
  }

  @Test
  void anAtomEntryTakesItsAddressFromTheAttribute() {
    Feed.Parsed parsed = Feed.parse(ATOM);
    assertEquals(1, parsed.items().size());
    assertEquals("https://example.com/only", parsed.items().get(0).link());
    assertEquals(Instant.parse("2026-08-24T09:00:00Z"), parsed.items().get(0).published());
  }

  @Test
  void theFeedsOwnBuildDateWinsOverTheLatestItem() {
    assertEquals(Instant.parse("2026-08-25T10:00:00Z"), Feed.parse(RSS).effectiveDate());
  }

  @Test
  void withoutOneTheLatestItemsPublicationDateStandsInForIt() {
    String withoutBuildDate = RSS.replaceAll("<lastBuildDate>.*</lastBuildDate>", "");
    assertEquals(
        Instant.parse("2026-08-25T09:00:00Z"), Feed.parse(withoutBuildDate).effectiveDate());
  }

  @Test
  void aFeedStatingNoDateAnywhereComparesAsOlderThanAnythingStored() {
    String undated =
        "<rss><channel><item><title>x</title><link>https://e.com/x</link></item></channel></rss>";
    assertEquals(Instant.EPOCH, Feed.parse(undated).effectiveDate());
    assertNull(Feed.parse(undated).items().get(0).published());
  }

  @Test
  void anUnreadableDateIsAbsentRatherThanTheEpoch() {
    assertNull(Feed.instant("last Tuesday"));
    assertNull(Feed.instant(""));
  }

  @Test
  void anEmptyDocumentHasNoItemsAndNoBuildDate() {
    Feed.Parsed parsed = Feed.parse("");
    assertNull(parsed.lastBuildDate());
    assertEquals(0, parsed.items().size());
  }
}

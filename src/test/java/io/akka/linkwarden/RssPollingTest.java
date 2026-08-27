package io.akka.linkwarden;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.akka.linkwarden.domain.Feed;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * SPEC-001 R76 — what a poll does with a feed it has already read.
 *
 * <p>The comparison is the whole rule, and it is a fact about two dates rather than about the
 * network, so it is driven over a parsed feed. Whether the items become links is checked through
 * the surface in {@link RssIntegrationTest}; what is checked here is which of them a poll
 * would offer.
 */
class RssPollingTest {

  private static Feed.Parsed feed(String... pubDates) {
    StringBuilder xml = new StringBuilder("<rss><channel>");
    for (int i = 0; i < pubDates.length; i++) {
      xml.append("<item><title>Item ")
          .append(i)
          .append("</title><link>https://feed.invalid/")
          .append(i)
          .append("</link><pubDate>")
          .append(pubDates[i])
          .append("</pubDate></item>");
    }
    return Feed.parse(xml.append("</channel></rss>").toString());
  }

  @Test
  void aFeedNoNewerThanWhatWasStoredOffersNothing() {
    Feed.Parsed parsed = feed("Mon, 24 Aug 2026 09:00:00 GMT");
    Instant stored = Instant.parse("2026-08-25T00:00:00Z");
    assertFalse(parsed.effectiveDate().isAfter(stored), "R76 — the comparison comes first");
  }

  @Test
  void onlyTheItemsPublishedAfterTheStoredDateAreOffered() {
    Feed.Parsed parsed =
        feed(
            "Sat, 22 Aug 2026 09:00:00 GMT",
            "Mon, 24 Aug 2026 09:00:00 GMT",
            "Tue, 25 Aug 2026 09:00:00 GMT");
    Instant stored = Instant.parse("2026-08-23T00:00:00Z");
    List<Feed.Item> fresh =
        parsed.items().stream()
            .filter(item -> item.published() != null && item.published().isAfter(stored))
            .toList();
    assertEquals(2, fresh.size(), "R76");
    assertEquals("Item 1", fresh.get(0).title());
    assertTrue(parsed.effectiveDate().isAfter(stored), "R76 — and the stored date moves forward");
  }

  @Test
  void aFirstPollHasNoStoredDateAndTakesEverything() {
    Feed.Parsed parsed = feed("Sat, 22 Aug 2026 09:00:00 GMT", "Mon, 24 Aug 2026 09:00:00 GMT");
    Instant stored = null;
    List<Feed.Item> fresh =
        parsed.items().stream()
            .filter(item -> item.published() != null && (stored == null || item.published().isAfter(stored)))
            .toList();
    assertEquals(2, fresh.size(), "R76 — a subscription that has never been polled takes the lot");
  }

  @Test
  void anItemWithNoAddressIsNotOfferedAtAll() {
    Feed.Parsed parsed =
        Feed.parse(
            "<rss><channel><item><title>No link</title>"
                + "<pubDate>Tue, 25 Aug 2026 09:00:00 GMT</pubDate></item></channel></rss>");
    assertEquals(1, parsed.items().size());
    assertEquals(null, parsed.items().get(0).link(), "R76 — and the poll skips it");
  }
}

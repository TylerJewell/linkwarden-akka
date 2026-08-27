package io.akka.linkwarden.application;

import io.akka.linkwarden.domain.Config;
import io.akka.linkwarden.domain.Feed;
import io.akka.linkwarden.domain.Ids;
import io.akka.linkwarden.domain.Records;
import io.akka.linkwarden.domain.Ssrf;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Turning a feed into links. SPEC-001 R76.
 *
 * <p>A poll is a comparison before it is anything else: unless the feed's date is after the stored
 * one, nothing is read and nothing is written. What that protects is not effort but duplication —
 * a feed that keeps its items forever would otherwise re-save all of them on every round.
 */
public final class RssPoller {

  /** What one poll did, which is what a test and the endpoint both read. */
  public record Result(int created, int skipped, boolean advanced) {}

  private final Data data;
  private final Config config;
  private final LinkWriter writer;
  private final HttpClient client;

  public RssPoller(Data data, Config config, LinkWriter writer) {
    this.data = data;
    this.config = config;
    this.writer = writer;
    this.client =
        HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(10))
            .build();
  }

  /** Fetches the feed through the same guard every other outbound call goes through. */
  public Result poll(Records.RssSubscription subscription, Instant now) {
    String xml = fetch(subscription.url());
    if (xml == null) return new Result(0, 0, false);
    return apply(subscription, Feed.parse(xml), now);
  }

  /**
   * SPEC-001 R76 — the rule itself, over an already-parsed feed.
   *
   * <p>Separate from the fetch so that what a poll decides can be driven without a network: the
   * feed is what the rule reads, and where the bytes came from is not part of it.
   */
  public Result apply(Records.RssSubscription subscription, Feed.Parsed feed, Instant now) {
    Instant stored = subscription.lastBuildDate();
    Instant feedDate = feed.effectiveDate();
    if (stored != null && !feedDate.isAfter(stored)) return new Result(0, 0, false);

    List<Feed.Item> fresh = new ArrayList<>();
    for (Feed.Item item : feed.items()) {
      if (item.published() == null) continue;
      if (stored != null && !item.published().isAfter(stored)) continue;
      fresh.add(item);
    }

    Optional<Records.User> owner = data.user(subscription.ownerId());
    if (owner.isEmpty()) return new Result(0, fresh.size(), false);
    // Capacity is asked once for the whole batch, and failing it leaves the stored date alone,
    // so the same items are offered again on the next round.
    if (writer.hasPassedLimit(owner.get(), fresh.size(), now)) {
      return new Result(0, fresh.size(), false);
    }

    Optional<Records.Collection> collection = data.collection(subscription.collectionId());
    if (collection.isEmpty()) return new Result(0, fresh.size(), false);

    int created = 0;
    int skipped = 0;
    for (Feed.Item item : fresh) {
      if (item.link() == null || !Ssrf.isSafe(item.link(), config, Ssrf.SYSTEM_LOOKUP)) {
        skipped++;
        continue;
      }
      writer.create(
          owner.get(),
          collection.get(),
          new LinkWriter.Proposal(
              item.title(), item.link(), "url", null, null, null, List.of(), null),
          false,
          now);
      created++;
    }
    data.client()
        .forKeyValueEntity(Ids.rss(subscription.id()))
        .method(RssSubscriptionEntity::advanceBuildDate)
        .invoke(feedDate);
    return new Result(created, skipped, true);
  }

  private String fetch(String url) {
    try {
      URI safe = Ssrf.assertSafe(url, config, Ssrf.SYSTEM_LOOKUP);
      HttpRequest request =
          HttpRequest.newBuilder(safe)
              .timeout(Duration.ofSeconds(10))
              .header("User-Agent", "Linkwarden (Server-Side Fetch)")
              .GET()
              .build();
      return client.send(request, HttpResponse.BodyHandlers.ofString()).body();
    } catch (Exception e) {
      // A feed that cannot be read is a round that did nothing, not an error a caller sees: the
      // subscription stays and the next round tries again.
      return null;
    }
  }
}

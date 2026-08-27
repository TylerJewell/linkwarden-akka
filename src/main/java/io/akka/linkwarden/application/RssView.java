package io.akka.linkwarden.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.Consume;
import akka.javasdk.annotations.Query;
import akka.javasdk.view.TableUpdater;
import akka.javasdk.view.View;
import io.akka.linkwarden.domain.Records;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Feed subscriptions, indexed by owner. */
@Component(id = "rss-view")
public class RssView extends View {

  public record RssRow(
      int id,
      String url,
      String name,
      Optional<Instant> lastBuildDate,
      int collectionId,
      int ownerId,
      boolean deleted) {}

  public record RssRows(List<RssRow> subscriptions) {}

  @Consume.FromKeyValueEntity(RssSubscriptionEntity.class)
  public static class RssUpdater extends TableUpdater<RssRow> {
    public Effect<RssRow> onChange(Records.RssSubscription subscription) {
      return effects()
          .updateRow(
              new RssRow(
                  subscription.id(),
                  subscription.url(),
                  subscription.name(),
                  Optional.ofNullable(subscription.lastBuildDate()),
                  subscription.collectionId(),
                  subscription.ownerId(),
                  subscription.deleted()));
    }
  }

  @Query(
      "SELECT * AS subscriptions FROM rss"
          + " WHERE deleted = false AND ownerId = :ownerId ORDER BY id ASC")
  public QueryEffect<RssRows> ownedBy(int ownerId) {
    return queryResult();
  }

  @Query("SELECT * AS subscriptions FROM rss WHERE deleted = false ORDER BY id ASC")
  public QueryEffect<RssRows> all() {
    return queryResult();
  }
}

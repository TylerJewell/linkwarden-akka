package io.akka.linkwarden.application;

import akka.Done;
import akka.javasdk.annotations.Component;
import akka.javasdk.keyvalueentity.KeyValueEntity;
import io.akka.linkwarden.domain.Records;
import java.time.Instant;

/** One feed polled into a collection. SPEC-001 R75–R77. */
@Component(id = "rss-subscription")
public class RssSubscriptionEntity extends KeyValueEntity<Records.RssSubscription> {

  public record Create(
      int id, String url, String name, int collectionId, int ownerId, Instant now) {}

  public Effect<Records.RssSubscription> create(Create cmd) {
    Records.RssSubscription subscription =
        new Records.RssSubscription(
            cmd.id(), cmd.url(), cmd.name(), null, cmd.collectionId(), cmd.ownerId(), cmd.now(),
            cmd.now(), false);
    return effects().updateState(subscription).thenReply(subscription);
  }

  public ReadOnlyEffect<Records.RssSubscription> get() {
    Records.RssSubscription subscription = currentState();
    if (subscription == null || subscription.deleted()) {
      return effects().error("RSS subscription not found.");
    }
    return effects().reply(subscription);
  }

  /** SPEC-001 R76 — advanced only after the batch of new items was created. */
  public Effect<Done> advanceBuildDate(Instant lastBuildDate) {
    Records.RssSubscription subscription = currentState();
    if (subscription == null || subscription.deleted()) {
      return effects().error("RSS subscription not found.");
    }
    return effects()
        .updateState(
            new Records.RssSubscription(
                subscription.id(), subscription.url(), subscription.name(), lastBuildDate,
                subscription.collectionId(), subscription.ownerId(), subscription.createdAt(),
                lastBuildDate, false))
        .thenReply(Done.getInstance());
  }

  public Effect<Done> delete(Instant now) {
    Records.RssSubscription subscription = currentState();
    if (subscription == null || subscription.deleted()) return effects().reply(Done.getInstance());
    return effects()
        .updateState(
            new Records.RssSubscription(
                subscription.id(), subscription.url(), subscription.name(),
                subscription.lastBuildDate(), subscription.collectionId(), subscription.ownerId(),
                subscription.createdAt(), now, true))
        .thenReply(Done.getInstance());
  }
}

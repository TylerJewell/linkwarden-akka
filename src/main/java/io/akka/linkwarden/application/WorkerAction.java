package io.akka.linkwarden.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.timedaction.TimedAction;
import akka.javasdk.timer.TimerScheduler;
import io.akka.linkwarden.domain.Candidate;
import io.akka.linkwarden.domain.Config;
import io.akka.linkwarden.domain.Ids;
import io.akka.linkwarden.domain.BatchSelection;
import io.akka.linkwarden.domain.Records;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The three loops the original runs in a worker process beside the web application.
 *
 * <p>Preserving, indexing and polling feeds are one pass here rather than three processes, and
 * each pass arms the next when it finishes rather than running on a fixed schedule — a pass that
 * takes longer than the interval would otherwise overlap itself, and every link in the batch
 * would be picked twice.
 */
@Component(id = "worker")
public class WorkerAction extends TimedAction {

  public static final String SWEEP_TIMER = "linkwarden-worker-sweep";

  private final ComponentClient componentClient;
  private final TimerScheduler timers;
  private final Config config;
  private final Data data;
  private final RssPoller poller;

  public WorkerAction(
      ComponentClient componentClient,
      TimerScheduler timers,
      Config config,
      Data data,
      RssPoller poller) {
    this.componentClient = componentClient;
    this.timers = timers;
    this.config = config;
    this.data = data;
    this.poller = poller;
  }

  public Effect sweep(String ignored) {
    Instant now = Instant.now();
    try {
      preserve(now);
      new Indexer(data, config).round(now);
      pollFeeds(now);
    } finally {
      // Armed in a finally so that one bad round does not stop every later one: the pass that
      // threw is lost, the loop is not.
      arm(componentClient, timers, interval(config));
    }
    return effects().done();
  }

  /** SPEC-001 R48–R49 — one shared batch, and every owner who had an eligible link stamped. */
  private void preserve(Instant now) {
    List<Candidate> candidates = new ArrayList<>();
    for (LinksView.LinkRow row : data.linkRowsAwaitingPreservation()) {
      Optional<Records.Collection> collection = data.collection(row.collectionId());
      if (collection.isEmpty()) continue;
      Optional<Records.User> owner = data.user(collection.get().ownerId());
      candidates.add(
          new Candidate(
              String.valueOf(row.id()),
              String.valueOf(collection.get().ownerId()),
              row.url().orElse(null),
              row.createdAt(),
              row.lastPreserved().orElse(null),
              owner.map(Records.User::lastPickedAt).orElse(null),
              row.indexVersion()));
    }
    BatchSelection.Batch batch =
        BatchSelection.pick(
            candidates, config.number("ARCHIVE_TAKE_COUNT", Config.DEFAULT_ARCHIVE_TAKE));

    for (String ownerId : batch.stampedOwnerIds()) {
      componentClient
          .forKeyValueEntity(Ids.user(Integer.parseInt(ownerId)))
          .method(UserEntity::markPicked)
          .invoke(now);
    }
    for (String linkId : batch.linkIds()) {
      componentClient
          .forWorkflow(Ids.archiveRun(Integer.parseInt(linkId), now))
          .method(LinkArchiveWorkflow::start)
          .invoke(Integer.parseInt(linkId));
    }
  }

  private void pollFeeds(Instant now) {
    for (RssView.RssRow row : data.client().forView().method(RssView::all).invoke().subscriptions()) {
      data.feed(row.id()).ifPresent(feed -> poller.poll(feed, now));
    }
  }

  public static Duration interval(Config config) {
    return Duration.ofSeconds(
        config.number("WORKER_INTERVAL", Config.DEFAULT_WORKER_INTERVAL_SECONDS));
  }

  public static void arm(ComponentClient componentClient, TimerScheduler timers, Duration after) {
    timers.createSingleTimer(
        SWEEP_TIMER,
        after,
        componentClient.forTimedAction().method(WorkerAction::sweep).deferred("sweep"));
  }
}

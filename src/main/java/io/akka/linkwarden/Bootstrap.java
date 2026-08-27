package io.akka.linkwarden;

import akka.javasdk.DependencyProvider;
import akka.javasdk.ServiceSetup;
import akka.javasdk.annotations.Setup;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.timer.TimerScheduler;
import io.akka.linkwarden.application.ChangeFeed;
import io.akka.linkwarden.application.Data;
import io.akka.linkwarden.application.FileStore;
import io.akka.linkwarden.application.Fetcher;
import io.akka.linkwarden.application.LinkWriter;
import io.akka.linkwarden.application.RssPoller;
import io.akka.linkwarden.application.WorkerAction;
import io.akka.linkwarden.domain.Config;

/**
 * The seven things every route needs and none of them holds itself: the instance's configuration,
 * the file store, the outbound fetcher, the reads and writes over the entities, the rules every
 * route that saves a link shares, the feed poller built on those, and the
 * feed of changes the screens subscribe to.
 */
@Setup
public class Bootstrap implements ServiceSetup {

  private final Config config = Config.fromEnvironment();
  private final FileStore fileStore = new FileStore(config);
  private final Fetcher fetcher = new Fetcher(config);
  private final Data data;
  private final LinkWriter linkWriter;
  private final RssPoller rssPoller;
  private final ChangeFeed changeFeed = new ChangeFeed();

  private final ComponentClient client;
  private final TimerScheduler timers;

  public Bootstrap(ComponentClient client, TimerScheduler timers) {
    this.client = client;
    this.timers = timers;
    this.data = new Data(client);
    this.linkWriter = new LinkWriter(data, config, fetcher);
    this.rssPoller = new RssPoller(data, config, linkWriter);
  }

  /** The worker's first pass, which arms the one after it. */
  @Override
  public void onStartup() {
    WorkerAction.arm(client, timers, WorkerAction.interval(config));
  }

  @Override
  public DependencyProvider createDependencyProvider() {
    return new DependencyProvider() {
      @Override
      @SuppressWarnings("unchecked")
      public <T> T getDependency(Class<T> type) {
        if (type == Config.class) return (T) config;
        if (type == FileStore.class) return (T) fileStore;
        if (type == Fetcher.class) return (T) fetcher;
        if (type == Data.class) return (T) data;
        if (type == LinkWriter.class) return (T) linkWriter;
        if (type == RssPoller.class) return (T) rssPoller;
        if (type == ChangeFeed.class) return (T) changeFeed;
        throw new IllegalArgumentException("nothing provides " + type.getName());
      }
    };
  }
}

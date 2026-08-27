package io.akka.linkwarden.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.Consume;
import akka.javasdk.consumer.Consumer;
import io.akka.linkwarden.domain.Records;
import java.time.Instant;

/**
 * The four records a screen watches, each announcing its own changes. SPEC-001 R99.
 *
 * <p>One consumer per record rather than one over all four: a consumer is bound to the component
 * it reads, so the alternative is not fewer classes but a class that cannot say which record
 * moved. The announcement carries the kind and the identifier and nothing else — what a screen
 * shows is read afterwards, through the same permission rules a request goes through, so a change
 * to something the watcher may not see cannot leak through the announcement.
 */
public final class ChangeConsumers {

  private ChangeConsumers() {}

  @Component(id = "link-changes")
  @Consume.FromKeyValueEntity(LinkEntity.class)
  public static class Links extends Consumer {

    private final ChangeFeed feed;

    public Links(ChangeFeed feed) {
      this.feed = feed;
    }

    public Effect onChange(Records.Link link) {
      feed.publish(new ChangeFeed.Change("link", link.id(), Instant.now()));
      return effects().done();
    }
  }

  @Component(id = "collection-changes")
  @Consume.FromKeyValueEntity(CollectionEntity.class)
  public static class Collections extends Consumer {

    private final ChangeFeed feed;

    public Collections(ChangeFeed feed) {
      this.feed = feed;
    }

    public Effect onChange(Records.Collection collection) {
      feed.publish(new ChangeFeed.Change("collection", collection.id(), Instant.now()));
      return effects().done();
    }
  }

  @Component(id = "tag-changes")
  @Consume.FromKeyValueEntity(TagEntity.class)
  public static class Tags extends Consumer {

    private final ChangeFeed feed;

    public Tags(ChangeFeed feed) {
      this.feed = feed;
    }

    public Effect onChange(Records.Tag tag) {
      feed.publish(new ChangeFeed.Change("tag", tag.id(), Instant.now()));
      return effects().done();
    }
  }

  @Component(id = "highlight-changes")
  @Consume.FromKeyValueEntity(HighlightEntity.class)
  public static class Highlights extends Consumer {

    private final ChangeFeed feed;

    public Highlights(ChangeFeed feed) {
      this.feed = feed;
    }

    public Effect onChange(Records.Highlight highlight) {
      feed.publish(new ChangeFeed.Change("highlight", highlight.id(), Instant.now()));
      return effects().done();
    }
  }
}

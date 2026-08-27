package io.akka.linkwarden.api;

import akka.http.javadsl.model.HttpResponse;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Delete;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Post;
import com.fasterxml.jackson.databind.JsonNode;
import io.akka.linkwarden.application.Data;
import io.akka.linkwarden.application.LinkWriter;
import io.akka.linkwarden.application.RssPoller;
import io.akka.linkwarden.application.RssSubscriptionEntity;
import io.akka.linkwarden.domain.Config;
import io.akka.linkwarden.domain.Ids;
import io.akka.linkwarden.domain.Records;
import io.akka.linkwarden.domain.Ssrf;
import io.akka.linkwarden.domain.Validation;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Feeds polled into a collection. SPEC-001 R75–R77. */
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
@HttpEndpoint("/api/v1")
public class RssEndpoint extends Surface {

  private final LinkWriter writer;
  private final RssPoller poller;

  public RssEndpoint(Data data, Config config, LinkWriter writer, RssPoller poller) {
    super(data, config);
    this.writer = writer;
    this.poller = poller;
  }

  @Get("/rss")
  public HttpResponse list() {
    Caller.Result result = signedIn();
    if (result.refused()) return result.refusal();
    return Answers.wrapped(
        200,
        data.feedsOwnedBy(result.user().id()).stream()
            .map(
                feed ->
                    Shapes.feed(
                        feed,
                        data.collection(feed.collectionId())
                            .map(Records.Collection::name)
                            .orElse(null)))
            .toList());
  }

  /** SPEC-001 R75 — the limit, the guard, the collection and the name, in that order. */
  @Post("/rss")
  public HttpResponse create(JsonNode body) {
    Caller.Result result = signedIn();
    if (result.refused()) return result.refusal();
    if (config.demoMode()) return Answers.demoRefusal();

    String name = Bodies.text(body, "name");
    String url = Bodies.text(body, "url");
    Optional<Validation.Issue> issue =
        Validation.first(
            Validation.requiredRawString("name", name, 50),
            url == null
                ? Optional.of(Validation.missing("url", "string"))
                : Validation.url("url", url),
            Validation.optionalRawString("url", url, 2048),
            Validation.optionalRawString(
                "collectionName", Bodies.text(body, "collectionName"), 50));
    if (issue.isPresent()) return Answers.issue(issue.get());

    Records.User user = result.user();
    int limit = config.rssSubscriptionLimitPerUser();
    if (data.feedsOwnedBy(user.id()).size() >= limit) {
      return Answers.wrapped(
          403, "You have reached the limit of " + limit + " RSS subscriptions.");
    }
    try {
      Ssrf.assertSafe(url, config, Ssrf.SYSTEM_LOOKUP);
    } catch (Ssrf.UnsafeUrlException e) {
      return Answers.wrapped(400, e.getMessage());
    }

    Instant now = Instant.now();
    JsonNode collectionNode = Bodies.child(body, "collection");
    Integer collectionId = Bodies.number(body, "collectionId");
    if (collectionId == null) collectionId = Bodies.number(collectionNode, "id");
    String collectionName = Bodies.text(body, "collectionName");
    if (collectionName == null) collectionName = Bodies.text(collectionNode, "name");

    LinkWriter.Resolution where =
        writer.resolveCollection(user, collectionId, collectionName, now);
    if (where.refused()) {
      return Answers.wrapped(
          403, "You do not have permission to add a link to this collection");
    }
    for (Records.RssSubscription existing : data.feedsOwnedBy(user.id())) {
      if (existing.name().equals(name)) {
        return Answers.wrapped(400, "RSS Subscription with that name already exists");
      }
    }

    int id = data.nextId("rss");
    Records.RssSubscription created =
        data.client()
            .forKeyValueEntity(Ids.rss(id))
            .method(RssSubscriptionEntity::create)
            .invoke(
                new RssSubscriptionEntity.Create(
                    id, url, name, where.collection().id(), user.id(), now));
    data.addTo(Ids.feedsOf(user.id()), id);
    // R75 — the feed is read once straight away, so a subscription that is already behind
    // catches up without waiting a whole polling interval.
    poller.poll(created, now);
    return Answers.wrapped(200, Shapes.feed(data.feed(id).orElse(created), null));
  }

  @Delete("/rss/{id}")
  public HttpResponse delete(int id) {
    Caller.Result result = signedIn();
    if (result.refused()) return result.refusal();

    Optional<Records.RssSubscription> found = data.feed(id);
    if (found.isEmpty()) return Answers.wrapped(404, "RSS subscription not found.");
    if (found.get().ownerId() != result.user().id()) {
      return Answers.wrapped(403, "Permission denied.");
    }
    data.client()
        .forKeyValueEntity(Ids.rss(id))
        .method(RssSubscriptionEntity::delete)
        .invoke(Instant.now());
    data.removeFrom(Ids.feedsOf(found.get().ownerId()), id);
    return Answers.wrapped(200, "RSS subscription deleted.");
  }
}

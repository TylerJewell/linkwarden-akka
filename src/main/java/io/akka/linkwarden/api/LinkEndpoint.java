package io.akka.linkwarden.api;

import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Delete;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Post;
import akka.javasdk.annotations.http.Put;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.http.AbstractHttpEndpoint;
import akka.javasdk.http.HttpResponses;
import akka.http.javadsl.model.HttpResponse;
import io.akka.linkwarden.application.LinkArchiveWorkflow;
import io.akka.linkwarden.application.LinkEntity;
import io.akka.linkwarden.application.LinkIndexWorkflow;
import io.akka.linkwarden.application.LinksView;
import io.akka.linkwarden.domain.ArchivalSettings;
import io.akka.linkwarden.domain.Link;
import io.akka.linkwarden.domain.PageFacts;
import io.akka.linkwarden.domain.Tag;
import java.time.Instant;
import java.util.List;

/** The capability's own surface: save a link, run an attempt over it, ask what it holds. */
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
@HttpEndpoint("/links")
public class LinkEndpoint extends AbstractHttpEndpoint {

  public record SaveLink(
      String linkId,
      String title,
      String url,
      String collectionId,
      String ownerId,
      List<Tag> tags,
      ArchivalSettings ownerSettings) {}

  public record ArchiveRequest(PageFacts facts) {}

  /**
   * A workflow that has run to its end cannot be transitioned again, so each attempt is its own
   * instance and the caller is told which one so it can be asked about afterwards.
   */
  public record ArchiveStarted(String runId, LinkArchiveWorkflow.Attempting attempt) {}

  private final ComponentClient client;

  public LinkEndpoint(ComponentClient client) {
    this.client = client;
  }

  @Post
  public HttpResponse save(SaveLink cmd) {
    client
        .forEventSourcedEntity(cmd.linkId())
        .method(LinkEntity::save)
        .invoke(
            new LinkEntity.Save(
                cmd.title() == null ? "" : cmd.title(),
                cmd.url(),
                cmd.collectionId(),
                cmd.ownerId(),
                cmd.tags(),
                cmd.ownerSettings() == null ? ArchivalSettings.NONE : cmd.ownerSettings(),
                Instant.now()));
    return HttpResponses.created();
  }

  @Get("/{linkId}")
  public LinkEntity.Status get(String linkId) {
    return client.forEventSourcedEntity(linkId).method(LinkEntity::status).invoke();
  }

  @Get
  public LinksView.LinkRows list() {
    return client.forView().method(LinksView::all).invoke();
  }

  @Get("/pending/preservation")
  public LinksView.LinkRows awaitingPreservation() {
    return client.forView().method(LinksView::awaitingPreservation).invoke();
  }

  @Get("/pending/indexing")
  public LinksView.LinkRows awaitingIndexing() {
    return client
        .forView()
        .method(LinksView::awaitingIndexing)
        .invoke(LinksView.currentIndexVersion());
  }

  /** Starts one attempt over the link. The reply names the run so it can be followed. */
  @Post("/{linkId}/archive")
  public ArchiveStarted archive(String linkId, ArchiveRequest request) {
    String runId = "archive-" + linkId + "-" + java.util.UUID.randomUUID();
    client
        .forWorkflow(runId)
        .method(LinkArchiveWorkflow::start)
        .invoke(
            new LinkArchiveWorkflow.Start(
                linkId, request.facts() == null ? PageFacts.ordinaryPage() : request.facts()));
    return new ArchiveStarted(
        runId, client.forWorkflow(runId).method(LinkArchiveWorkflow::state).invoke());
  }

  @Get("/{linkId}/archive/{runId}")
  public LinkArchiveWorkflow.Attempting archiveState(String linkId, String runId) {
    return client.forWorkflow(runId).method(LinkArchiveWorkflow::state).invoke();
  }

  /**
   * Hands the link to the search index. Whether the index accepts it is not this port's decision
   * (SPEC-001 §4 C), so it is read from the query string — and read explicitly, because a
   * parameter that is not in the path is not bound to the method's argument by anything.
   */
  @Post("/{linkId}/index")
  public LinkIndexWorkflow.Indexing index(String linkId) {
    boolean indexFails =
        requestContext().queryParams().getBoolean("indexFails").orElse(false);
    String id = "index-" + linkId + "-" + java.util.UUID.randomUUID();
    client
        .forWorkflow(id)
        .method(LinkIndexWorkflow::start)
        .invoke(new LinkIndexWorkflow.Start(linkId, indexFails));
    return client.forWorkflow(id).method(LinkIndexWorkflow::state).invoke();
  }

  /** R19 — one write re-opens both pipelines. */
  @Put("/{linkId}/archive")
  public HttpResponse requestReArchive(String linkId) {
    client
        .forEventSourcedEntity(linkId)
        .method(LinkEntity::requestReArchive)
        .invoke(Instant.now());
    return HttpResponses.ok();
  }

  @Delete("/{linkId}")
  public HttpResponse delete(String linkId) {
    client.forEventSourcedEntity(linkId).method(LinkEntity::delete).invoke(Instant.now());
    return HttpResponses.ok();
  }
}

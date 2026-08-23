package io.akka.linkwarden.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import akka.javasdk.testkit.TestKitSupport;
import io.akka.linkwarden.application.LinkArchiveWorkflow;
import io.akka.linkwarden.application.LinkEntity;
import io.akka.linkwarden.application.LinkIndexWorkflow;
import io.akka.linkwarden.application.LinksView;
import io.akka.linkwarden.domain.ArchivalSettings;
import io.akka.linkwarden.domain.PageFacts;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

/**
 * Driven through the HTTP surface rather than through the component client, because the two do
 * not exercise the same code: a parameter that is not in the path is bound by the endpoint's own
 * reading of the query string and by nothing else.
 */
public class LinkEndpointIntegrationTest extends TestKitSupport {

  private void save(String id, String url) {
    var response =
        httpClient
            .POST("/links")
            .withRequestBody(
                new LinkEndpoint.SaveLink(
                    id, "A link", url, "7", "owner-1", List.of(),
                    new ArchivalSettings(true, false, true, true, false, false)))
            .invoke();
    assertEquals(201, response.status().intValue());
  }

  @Test
  public void aLinkSavedThroughHttpIsArchivedAndListed() {
    save("ep-1", "https://example.test/a");

    var attempting =
        httpClient
            .POST("/links/ep-1/archive")
            .withRequestBody(new LinkEndpoint.ArchiveRequest(PageFacts.ordinaryPage()))
            .responseBodyAs(LinkEndpoint.ArchiveStarted.class)
            .invoke()
            .body();
    assertEquals("ep-1", attempting.attempt().linkId());

    Awaitility.await()
        .atMost(30, TimeUnit.SECONDS)
        .until(
            () ->
                httpClient
                    .GET("/links/ep-1")
                    .responseBodyAs(LinkEntity.Status.class)
                    .invoke()
                    .body()
                    .link()
                    .lastPreserved()
                    != null);

    // The view is fed from the entity's events, so the row arrives shortly after the write.
    Awaitility.await()
        .atMost(30, TimeUnit.SECONDS)
        .until(
            () ->
                httpClient
                    .GET("/links")
                    .responseBodyAs(LinksView.LinkRows.class)
                    .invoke()
                    .body()
                    .links()
                    .stream()
                    .anyMatch(r -> r.linkId().equals("ep-1")));
  }

  @Test
  public void theIndexFailureFlagIsReadFromTheQueryString() {
    save("ep-2", "https://example.test/b");

    var failing =
        httpClient
            .POST("/links/ep-2/index?indexFails=true")
            .responseBodyAs(LinkIndexWorkflow.Indexing.class)
            .invoke()
            .body();
    assertTrue(failing.indexFails());

    Awaitility.await()
        .atMost(30, TimeUnit.SECONDS)
        .until(
            () ->
                httpClient
                    .GET("/links/ep-2")
                    .responseBodyAs(LinkEntity.Status.class)
                    .invoke()
                    .body()
                    .awaitingIndexing());

    save("ep-3", "https://example.test/c");
    var succeeding =
        httpClient
            .POST("/links/ep-3/index")
            .responseBodyAs(LinkIndexWorkflow.Indexing.class)
            .invoke()
            .body();
    assertFalse(succeeding.indexFails());
  }

  @Test
  public void reArchiveThroughHttpReopensThePipeline() {
    save("ep-4", "https://example.test/d");
    httpClient
        .POST("/links/ep-4/archive")
        .withRequestBody(new LinkEndpoint.ArchiveRequest(PageFacts.ordinaryPage()))
        .invoke();
    Awaitility.await()
        .atMost(30, TimeUnit.SECONDS)
        .until(
            () ->
                !httpClient
                    .GET("/links/ep-4")
                    .responseBodyAs(LinkEntity.Status.class)
                    .invoke()
                    .body()
                    .awaitingPreservation());

    assertEquals(200, httpClient.PUT("/links/ep-4/archive").invoke().status().intValue());

    assertTrue(
        httpClient
            .GET("/links/ep-4")
            .responseBodyAs(LinkEntity.Status.class)
            .invoke()
            .body()
            .awaitingPreservation());
  }
}

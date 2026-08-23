package io.akka.linkwarden.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import akka.javasdk.testkit.TestKitSupport;
import io.akka.linkwarden.application.LinkEntity;
import io.akka.linkwarden.application.LinkIndexWorkflow;
import io.akka.linkwarden.domain.ArchivalSettings;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

/** SPEC-001 R17 and R21 against a running runtime. */
public class LinkIndexWorkflowIntegrationTest extends TestKitSupport {

  private String saveLink(String id) {
    componentClient
        .forEventSourcedEntity(id)
        .method(LinkEntity::save)
        .invoke(
            new LinkEntity.Save(
                "A link", "https://example.test/a", "7", "owner-1", List.of(),
                ArchivalSettings.NONE, java.time.Instant.now()));
    return id;
  }

  private LinkIndexWorkflow.Indexing runIndexing(String linkId, boolean indexFails) {
    String id = "index-" + linkId + "-" + java.util.UUID.randomUUID();
    componentClient
        .forWorkflow(id)
        .method(LinkIndexWorkflow::start)
        .invoke(new LinkIndexWorkflow.Start(linkId, indexFails));
    Awaitility.await()
        .atMost(60, TimeUnit.SECONDS)
        .until(
            () ->
                componentClient
                    .forWorkflow(id)
                    .method(LinkIndexWorkflow::state)
                    .invoke()
                    .finished());
    return componentClient.forWorkflow(id).method(LinkIndexWorkflow::state).invoke();
  }

  @Test
  public void aSucceedingIndexAttemptAdvancesTheVersion() {
    String id = saveLink("lw-index-ok");

    var indexing = runIndexing(id, false);

    assertTrue(indexing.indexed());
    assertEquals(1, indexing.attemptsMade());
    assertFalse(
        componentClient
            .forEventSourcedEntity(id)
            .method(LinkEntity::status)
            .invoke()
            .awaitingIndexing());
  }

  @Test
  public void aFailedIndexAttemptIsOfferedAgain() {
    String id = saveLink("lw-index-fail");

    var indexing = runIndexing(id, true);

    assertFalse(indexing.indexed());
    assertEquals(4, indexing.attemptsMade());
    assertTrue(
        componentClient
            .forEventSourcedEntity(id)
            .method(LinkEntity::status)
            .invoke()
            .awaitingIndexing());
  }
}

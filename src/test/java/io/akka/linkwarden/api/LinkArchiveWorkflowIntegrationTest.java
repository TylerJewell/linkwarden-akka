package io.akka.linkwarden.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import akka.javasdk.testkit.TestKitSupport;
import io.akka.linkwarden.application.LinkArchiveWorkflow;
import io.akka.linkwarden.application.LinkEntity;
import io.akka.linkwarden.domain.ArchivalSettings;
import io.akka.linkwarden.domain.PageFacts;
import io.akka.linkwarden.domain.PreservedFormats;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

/**
 * The pipeline driven end to end through a running runtime. These start a real service, which is
 * what separates them from the decision checks: a command carrying a record only round-trips
 * through the wire serializer here.
 */
public class LinkArchiveWorkflowIntegrationTest extends TestKitSupport {

  private static final ArchivalSettings ALL_ON =
      new ArchivalSettings(true, true, true, true, true, true);

  private String saveLink(String id, String url) {
    componentClient
        .forEventSourcedEntity(id)
        .method(LinkEntity::save)
        .invoke(
            new LinkEntity.Save("A link", url, "7", "owner-1", List.of(), ALL_ON, java.time.Instant.now()));
    return id;
  }

  /** A completed workflow cannot be transitioned again, so each attempt is its own run. */
  private LinkArchiveWorkflow.Attempting runAttempt(String linkId, PageFacts facts) {
    String runId = "archive-" + linkId + "-" + java.util.UUID.randomUUID();
    componentClient
        .forWorkflow(runId)
        .method(LinkArchiveWorkflow::start)
        .invoke(new LinkArchiveWorkflow.Start(linkId, facts));
    return awaitFinished(runId);
  }

  private LinkArchiveWorkflow.Attempting awaitFinished(String runId) {
    Awaitility.await()
        .atMost(60, TimeUnit.SECONDS)
        .until(
            () ->
                componentClient
                    .forWorkflow(runId)
                    .method(LinkArchiveWorkflow::state)
                    .invoke()
                    .finished());
    return componentClient.forWorkflow(runId).method(LinkArchiveWorkflow::state).invoke();
  }

  private LinkEntity.Status status(String linkId) {
    return componentClient.forEventSourcedEntity(linkId).method(LinkEntity::status).invoke();
  }

  @Test
  public void anOrdinaryPageIsPreservedInEveryFormat() {
    String id = saveLink("lw-ordinary", "https://example.test/a");

    runAttempt(id, PageFacts.ordinaryPage());

    var link = status(id).link();
    assertEquals("archives/preview/7/lw-ordinary.jpeg", link.formats().preview());
    assertEquals("archives/7/lw-ordinary_readability.json", link.formats().readable());
    assertEquals("archives/7/lw-ordinary.jpeg", link.formats().image());
    assertEquals("archives/7/lw-ordinary.pdf", link.formats().pdf());
    assertEquals("archives/7/lw-ordinary.html", link.formats().monolith());
    assertEquals("A page.", link.metaDescription());
    assertNotNull(link.lastPreserved());
    assertFalse(status(id).awaitingPreservation());
  }

  @Test
  public void aLinkThePipelineWillNotFetchIsMarkedUnavailable() {
    String id = saveLink("lw-skipped", "https://example.test/a");
    var disabled =
        new PageFacts(
            "text/html", null, "https://x.test", "d", "t", 4096, 1024, 2048, false, false, true,
            false);

    var attempting = runAttempt(id, disabled);

    var link = status(id).link();
    assertEquals(PreservedFormats.UNAVAILABLE, link.formats().image());
    assertEquals(PreservedFormats.UNAVAILABLE, link.formats().pdf());
    assertEquals(PreservedFormats.UNAVAILABLE, link.formats().readable());
    assertEquals(PreservedFormats.UNAVAILABLE, link.formats().monolith());
    assertEquals(PreservedFormats.UNAVAILABLE, link.formats().preview());
    assertNotNull(link.lastPreserved());
    assertNull(link.indexVersion());
    assertEquals(1, attempting.attemptsMade());
  }

  @Test
  public void everyAbsentFormatIsMarkedUnavailableWhenTheAttemptFinishes() {
    String id = saveLink("lw-partial", "https://example.test/a");
    // The page renders, but monolith cannot run — so four formats answer and one does not.
    var facts =
        new PageFacts(
            "text/html", null, "https://x.test", "d", "t", 4096, 1024, 2048, false, true, false,
            false);

    runAttempt(id, facts);

    var link = status(id).link();
    assertEquals(PreservedFormats.UNAVAILABLE, link.formats().monolith());
    assertEquals("archives/7/lw-partial.jpeg", link.formats().image());
  }

  @Test
  public void aFailingAttemptIsRetriedThenGivenUp() {
    String id = saveLink("lw-failing", "https://example.test/a");
    var failing =
        new PageFacts(
            "text/html", null, "https://x.test", "d", "t", 4096, 1024, 2048, true, false, false,
            false);

    var attempting = runAttempt(id, failing);

    // The test runtime's base wait is 200ms (src/test/resources/application.conf), so the four
    // attempts are the same four the five-second base would make.
    assertEquals(4, attempting.attemptsMade());
    assertEquals("page load", attempting.lastFailure());
    var link = status(id).link();
    assertEquals(PreservedFormats.UNAVAILABLE, link.formats().preview());
    assertNotNull(link.lastPreserved());
    assertFalse(status(id).awaitingPreservation());
  }

  @Test
  public void aDeletedLinkHasItsFilesRemoved() {
    String id = saveLink("lw-deleted", "https://example.test/a");
    componentClient
        .forEventSourcedEntity(id)
        .method(LinkEntity::delete)
        .invoke(java.time.Instant.now());

    var attempting = runAttempt(id, PageFacts.ordinaryPage());

    assertTrue(attempting.finished());
    var link = status(id).link();
    assertTrue(link.deleted());
    assertNull(link.lastPreserved());
  }

  @Test
  public void reArchiveReopensBothPipelines() {
    String id = saveLink("lw-rearchive", "https://example.test/a");
    runAttempt(id, PageFacts.ordinaryPage());
    componentClient.forEventSourcedEntity(id).method(LinkEntity::markIndexed).invoke();
    assertFalse(status(id).awaitingPreservation());
    assertFalse(status(id).awaitingIndexing());

    componentClient
        .forEventSourcedEntity(id)
        .method(LinkEntity::requestReArchive)
        .invoke(java.time.Instant.now());

    var after = status(id);
    assertTrue(after.awaitingPreservation());
    assertTrue(after.awaitingIndexing());
    assertNull(after.link().formats().image());
    assertNull(after.link().textContent());
  }

  @Test
  public void anAttemptResumesOnTheFormatsAlreadyStored() {
    String id = saveLink("lw-resume", "https://example.test/a");
    runAttempt(id, PageFacts.ordinaryPage());
    componentClient
        .forEventSourcedEntity(id)
        .method(LinkEntity::requestReArchive)
        .invoke(java.time.Instant.now());
    componentClient
        .forEventSourcedEntity(id)
        .method(LinkEntity::preserveFormat)
        .invoke(new LinkEntity.Preserve(
                io.akka.linkwarden.domain.Format.PREVIEW, "archives/preview/7/lw-resume.jpeg"));

    runAttempt(id, PageFacts.ordinaryPage());

    // The stored preview was left alone and everything else answered again.
    var link = status(id).link();
    assertEquals("archives/preview/7/lw-resume.jpeg", link.formats().preview());
    assertEquals("archives/7/lw-resume.jpeg", link.formats().image());
  }

  @Test
  public void theRetryWaitIsTheOneThePolicyNames() {
    // The workflow reads the base wait from configuration; the arithmetic over it is the domain's.
    var policy = io.akka.linkwarden.domain.RetryPolicy.DEFAULT;
    assertEquals(Duration.ofSeconds(5), policy.delayBefore(2));
    assertEquals(Duration.ofSeconds(20), policy.delayBefore(4));
  }
}

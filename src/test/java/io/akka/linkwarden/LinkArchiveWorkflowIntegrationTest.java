package io.akka.linkwarden;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import akka.javasdk.testkit.TestKitSupport;
import io.akka.linkwarden.application.CollectionEntity;
import io.akka.linkwarden.application.CounterEntity;
import io.akka.linkwarden.application.LinkArchiveWorkflow;
import io.akka.linkwarden.application.LinkEntity;
import io.akka.linkwarden.domain.Ids;
import io.akka.linkwarden.domain.Records;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

/**
 * SPEC-001 R48–R53 — one link through the preservation pipeline, at the level a caller observes.
 *
 * <p>The rules are asked of the finished link rather than of the runner, because the thing R53
 * settles is what a link looks like once the run is over, whatever happened during it. The
 * addresses used are ones the guard refuses, so the run reaches its terminal state without a
 * renderer and without the network.
 */
class LinkArchiveWorkflowIntegrationTest extends TestKitSupport {

  private int nextId(String kind) {
    return componentClient.forKeyValueEntity(kind).method(CounterEntity::take).invoke();
  }

  private int aLinkWith(String url) {
    Instant now = Instant.now();
    int owner = nextId("user");
    int collection = nextId("collection");
    componentClient
        .forKeyValueEntity(Ids.collection(collection))
        .method(CollectionEntity::create)
        .invoke(
            new CollectionEntity.Create(
                collection, "Box", null, null, null, null, null, owner, owner, List.of(), now));
    int link = nextId("link");
    componentClient
        .forKeyValueEntity(Ids.link(link))
        .method(LinkEntity::create)
        .invoke(
            new LinkEntity.Create(
                link, "A page", "url", "", collection, owner, url, List.of(), null, false, null,
                null, now));
    return link;
  }

  private Records.Link read(int link) {
    return componentClient.forKeyValueEntity(Ids.link(link)).method(LinkEntity::get).invoke();
  }

  private void archive(int link) {
    componentClient
        .forWorkflow("archive-test-" + link)
        .method(LinkArchiveWorkflow::start)
        .invoke(link);
    Awaitility.await()
        .atMost(60, TimeUnit.SECONDS)
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(
            () ->
                assertEquals(
                    "done",
                    componentClient
                        .forWorkflow("archive-test-" + link)
                        .method(LinkArchiveWorkflow::status)
                        .invoke()
                        .status()));
  }

  @Test
  void aRunThatProducesNothingStillFinishesTheLink() {
    int link = aLinkWith("https://unreachable.invalid/page");
    assertNotNull(read(link));

    archive(link);

    Records.Link after = read(link);
    assertNotNull(after.lastPreserved(), "R53 — the finishing instant is written either way");
    assertEquals(Records.Link.UNAVAILABLE, after.image(), "R52");
    assertEquals(Records.Link.UNAVAILABLE, after.pdf());
    assertEquals(Records.Link.UNAVAILABLE, after.readable());
    assertEquals(Records.Link.UNAVAILABLE, after.monolith());
    assertEquals(Records.Link.UNAVAILABLE, after.preview());
    assertEquals(null, after.indexVersion(), "R53 — and it is offered to the index");
  }

  @Test
  void aFinishedLinkIsNoLongerOfferedToThePipeline() {
    int link = aLinkWith("https://unreachable.invalid/second");
    archive(link);
    // R48 — a url and no lastPreserved is the whole of the offer, so a finished link fails it.
    assertFalse(read(link).lastPreserved() == null, "R48");
  }

  @Test
  void oneInstanceIsOneRunAndAskingItToStartAgainDoesNothing() {
    int link = aLinkWith("https://unreachable.invalid/third");
    archive(link);
    String second =
        componentClient
            .forWorkflow("archive-test-" + link)
            .method(LinkArchiveWorkflow::start)
            .invoke(link);
    assertEquals("already running", second, "a run that has happened is not repeated in place");
  }

  @Test
  void aLinkOfferedAgainRunsUnderAnInstanceOfItsOwn() {
    int link = aLinkWith("https://unreachable.invalid/fourth");
    archive(link);

    // What lets a link be preserved a second time is a fresh instance; the same one would refuse.
    String key = Ids.archiveRun(link, Instant.parse("2026-08-26T12:00:00Z"));
    assertEquals(
        "started",
        componentClient.forWorkflow(key).method(LinkArchiveWorkflow::start).invoke(link));
  }
}

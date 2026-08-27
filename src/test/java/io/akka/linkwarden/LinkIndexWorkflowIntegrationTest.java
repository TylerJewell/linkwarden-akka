package io.akka.linkwarden;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import akka.javasdk.testkit.TestKitSupport;
import io.akka.linkwarden.application.CollectionEntity;
import io.akka.linkwarden.application.CounterEntity;
import io.akka.linkwarden.application.Data;
import io.akka.linkwarden.application.IndexDocumentEntity;
import io.akka.linkwarden.application.Indexer;
import io.akka.linkwarden.application.LinkEntity;
import io.akka.linkwarden.domain.Config;
import io.akka.linkwarden.domain.Eligibility;
import io.akka.linkwarden.domain.Ids;
import io.akka.linkwarden.domain.IndexDocument;
import io.akka.linkwarden.domain.Permissions;
import io.akka.linkwarden.domain.Records;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

/** SPEC-001 R57–R59 — what the index is given, and what a finished round writes back. */
class LinkIndexWorkflowIntegrationTest extends TestKitSupport {

  private int nextId(String kind) {
    return componentClient.forKeyValueEntity(kind).method(CounterEntity::take).invoke();
  }

  /** A collection with one member, so the document has a member list to carry. */
  private int aCollection(int owner, int member, boolean isPublic, Instant now) {
    int id = nextId("collection");
    componentClient
        .forKeyValueEntity(Ids.collection(id))
        .method(CollectionEntity::create)
        .invoke(
            new CollectionEntity.Create(
                id, "Indexed", null, null, null, null, null, owner, owner,
                List.of(Permissions.Member.full(member)), now));
    if (isPublic) {
      componentClient
          .forKeyValueEntity(Ids.collection(id))
          .method(CollectionEntity::update)
          .invoke(
              new CollectionEntity.Update(
                  "Indexed", null, null, null, null, true, null,
                  List.of(Permissions.Member.full(member)), now));
    }
    return id;
  }

  @Test
  void theDocumentCarriesTheLinkAndWhatASearchNeedsToKnowAboutItsCollection() {
    Instant now = Instant.parse("2026-08-20T10:00:00Z");
    int owner = nextId("user");
    int member = nextId("user");
    int collection = aCollection(owner, member, true, now);
    int link = nextId("link");
    componentClient
        .forKeyValueEntity(Ids.link(link))
        .method(LinkEntity::create)
        .invoke(
            new LinkEntity.Create(
                link, "A page", "url", "About a page", collection, owner,
                "https://indexed.invalid/a", List.of(), null, false, null, null, now));

    Indexer indexer = new Indexer(new Data(componentClient), Config.fromEnvironment());
    Records.Link stored =
        componentClient.forKeyValueEntity(Ids.link(link)).method(LinkEntity::get).invoke();
    Records.Collection holder =
        componentClient
            .forKeyValueEntity(Ids.collection(collection))
            .method(CollectionEntity::get)
            .invoke();
    IndexDocument document = indexer.document(stored, holder);

    assertEquals(link, document.id(), "R58");
    assertEquals("A page", document.name());
    assertEquals(owner, document.collectionOwnerId());
    assertEquals(List.of(member), document.collectionMemberIds());
    assertTrue(document.collectionIsPublic());
    assertEquals("Indexed", document.collectionName());
    assertEquals(now.getEpochSecond(), document.creationTimestamp(), "R58 — whole seconds");
    assertEquals(Eligibility.CURRENT_INDEX_VERSION, document.indexVersion());
  }

  @Test
  void theFilterableAndSortableAttributesAreExactlyWhatIsDeclared() {
    assertEquals(11, IndexDocument.FILTERABLE.size(), "R58 — eleven, no more and no fewer");
    assertEquals(List.of("id", "name"), IndexDocument.SORTABLE, "R58");
    assertTrue(IndexDocument.FILTERABLE.contains("creationTimestamp"));
    assertTrue(!IndexDocument.FILTERABLE.contains("collectionId"), "R58 — and not this one");
  }

  @Test
  void aRoundWritesTheDocumentAndThenTheVersion() {
    Instant now = Instant.now();
    int owner = nextId("user");
    int collection = aCollection(owner, nextId("user"), false, now);
    int link = nextId("link");
    componentClient
        .forKeyValueEntity(Ids.link(link))
        .method(LinkEntity::create)
        .invoke(
            new LinkEntity.Create(
                link, "To index", "url", "", collection, owner, "https://indexed.invalid/b",
                List.of(), null, false, null, null, now));

    // Which links are waiting is read from the projection the indexing loop runs off, so the
    // round is repeated until the new link has reached it — the lag is the loop's, not a rule's.
    Indexer indexer = new Indexer(new Data(componentClient), Config.fromEnvironment());
    Awaitility.await()
        .atMost(30, TimeUnit.SECONDS)
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(() -> assertTrue(indexer.round(Instant.now()).contains(link), "R57"));

    IndexDocument written =
        componentClient
            .forKeyValueEntity(Ids.link(link))
            .method(IndexDocumentEntity::get)
            .invoke();
    assertEquals("To index", written.name());
    assertEquals(
        Eligibility.CURRENT_INDEX_VERSION,
        componentClient
            .forKeyValueEntity(Ids.link(link))
            .method(LinkEntity::get)
            .invoke()
            .indexVersion(),
        "R59");

    Awaitility.await()
        .atMost(30, TimeUnit.SECONDS)
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(
            () ->
                assertTrue(
                    !indexer.round(Instant.now()).contains(link),
                    "R57 — a link at the current version is not offered again"));
  }
}

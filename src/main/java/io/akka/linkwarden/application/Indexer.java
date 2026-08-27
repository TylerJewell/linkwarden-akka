package io.akka.linkwarden.application;

import io.akka.linkwarden.domain.Candidate;
import io.akka.linkwarden.domain.Config;
import io.akka.linkwarden.domain.Eligibility;
import io.akka.linkwarden.domain.Ids;
import io.akka.linkwarden.domain.IndexBatch;
import io.akka.linkwarden.domain.IndexDocument;
import io.akka.linkwarden.domain.Permissions;
import io.akka.linkwarden.domain.Records;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** One indexing round. SPEC-001 R57–R59. */
public final class Indexer {

  private final Data data;
  private final Config config;

  public Indexer(Data data, Config config) {
    this.data = data;
    this.config = config;
  }

  /** @return the identifiers indexed this round */
  public List<Integer> round(Instant now) {
    List<Candidate> candidates = new ArrayList<>();
    for (LinksView.LinkRow row : data.linkRowsAwaitingIndexing()) {
      candidates.add(
          new Candidate(
              String.valueOf(row.id()),
              String.valueOf(row.collectionId()),
              row.url().orElse(null),
              row.createdAt(),
              row.lastPreserved().orElse(null),
              null,
              row.indexVersion()));
    }
    List<String> picked = IndexBatch.pick(candidates, config.number("MEILI_TAKE_COUNT", Config.DEFAULT_INDEX_TAKE));

    List<Integer> indexed = new ArrayList<>();
    for (String id : picked) {
      int linkId = Integer.parseInt(id);
      Optional<Records.Link> link = data.link(linkId);
      if (link.isEmpty()) continue;
      Optional<Records.Collection> collection = data.collection(link.get().collectionId());
      if (collection.isEmpty()) continue;

      data.client()
          .forKeyValueEntity(Ids.link(linkId))
          .method(IndexDocumentEntity::write)
          .invoke(document(link.get(), collection.get()));
      // R59 — the version is written after the document, so a round interrupted between the
      // two leaves the link eligible rather than marked done with nothing written.
      data.client()
          .forKeyValueEntity(Ids.link(linkId))
          .method(LinkEntity::setIndexVersion)
          .invoke(new LinkEntity.SetIndexVersion(Eligibility.CURRENT_INDEX_VERSION, now));
      indexed.add(linkId);
    }
    return indexed;
  }

  /** SPEC-001 R58 — the link's own fields, plus what a search needs to know about its collection. */
  public IndexDocument document(Records.Link link, Records.Collection collection) {
    return new IndexDocument(
        link.id(),
        link.name(),
        link.url(),
        link.description(),
        link.type(),
        link.textContent(),
        link.collectionId(),
        collection.ownerId(),
        collection.members().stream().map(Permissions.Member::userId).toList(),
        collection.isPublic(),
        collection.name(),
        data.tagsOf(link).stream().map(Records.Tag::name).toList(),
        link.pinnedBy(),
        link.createdAt().getEpochSecond(),
        Eligibility.CURRENT_INDEX_VERSION);
  }
}

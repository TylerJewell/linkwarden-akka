package io.akka.linkwarden.application;

import akka.Done;
import akka.javasdk.annotations.Component;
import akka.javasdk.keyvalueentity.KeyValueEntity;
import io.akka.linkwarden.domain.IndexDocument;

/**
 * The document the search index holds for one link. SPEC-001 R58.
 *
 * <p>Kept as a record of its own rather than derived from the link on demand, because the whole
 * point of R57's index version is that the document can be older than the link: a link changed
 * since the last indexing round has a document that no longer matches it, and that difference is
 * what the round exists to close.
 */
@Component(id = "index-document")
public class IndexDocumentEntity extends KeyValueEntity<IndexDocument> {

  public ReadOnlyEffect<IndexDocument> get() {
    if (currentState() == null) return effects().error("Nothing indexed under this identifier.");
    return effects().reply(currentState());
  }

  public Effect<Done> write(IndexDocument document) {
    return effects().updateState(document).thenReply(Done.getInstance());
  }

  public Effect<Done> remove() {
    return effects().deleteEntity().thenReply(Done.getInstance());
  }
}

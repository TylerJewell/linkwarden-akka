package io.akka.linkwarden.application;

import io.akka.linkwarden.domain.Capacity;
import io.akka.linkwarden.domain.Config;
import io.akka.linkwarden.domain.Ids;
import io.akka.linkwarden.domain.Permissions;
import io.akka.linkwarden.domain.Records;
import io.akka.linkwarden.domain.Urls;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Saving a link, from the three places that save one. SPEC-001 R34–R40.
 *
 * <p>The HTTP route, an RSS poll and an import all create links, and all three resolve the
 * collection, check capacity, refuse duplicates and attach tags by the same rules. The rules live
 * here rather than in the route so the other two cannot drift from it.
 */
public final class LinkWriter {

  /** What the collection a link is being saved into resolved to, or why it did not. */
  public record Resolution(Records.Collection collection, int status, String message) {

    public boolean refused() {
      return collection == null;
    }

    static Resolution of(Records.Collection collection) {
      return new Resolution(collection, 0, null);
    }

    static Resolution refusedWith(int status, String message) {
      return new Resolution(null, status, message);
    }
  }

  public static final String UNORGANIZED = "Unorganized";

  private final Data data;
  private final Config config;
  private final Fetcher fetcher;

  public LinkWriter(Data data, Config config, Fetcher fetcher) {
    this.data = data;
    this.config = config;
    this.fetcher = fetcher;
  }

  // ------------------------------------------------------------------
  // where a link goes
  // ------------------------------------------------------------------

  /** SPEC-001 R34 — by id, else by name, else the caller's own Unorganized. */
  public Resolution resolveCollection(
      Records.User user, Integer collectionId, String collectionName, Instant now) {
    if (collectionId != null && collectionId > 0) {
      Optional<Records.Collection> found = data.collection(collectionId);
      if (found.isEmpty() || !Permissions.canCreate(found.get().asSubject(), user.id())) {
        return Resolution.refusedWith(400, "Collection is not accessible.");
      }
      return Resolution.of(found.get());
    }
    String name = collectionName == null ? null : collectionName.trim();
    if (name != null && !name.isEmpty() && !name.equals(UNORGANIZED)) {
      // A name that is not Unorganized makes a new collection every time it is used: the name
      // is not looked up first.
      return Resolution.of(createCollection(user, name, now));
    }
    return Resolution.of(unorganized(user, now));
  }

  /** The caller's own top-level Unorganized, created the first time it is needed. */
  public Records.Collection unorganized(Records.User user, Instant now) {
    for (Records.Collection collection : data.reachableCollections(user.id())) {
      if (collection.ownerId() == user.id()
          && collection.parentId() == null
          && UNORGANIZED.equals(collection.name())) {
        return collection;
      }
    }
    return createCollection(user, UNORGANIZED, now);
  }

  /** SPEC-001 R30, R34 — a collection made on a link's behalf still joins the caller's order. */
  public Records.Collection createCollection(Records.User user, String name, Instant now) {
    int id = data.nextId("collection");
    data.client()
        .forKeyValueEntity(Ids.collection(id))
        .method(CollectionEntity::create)
        .invoke(
            new CollectionEntity.Create(
                id, name, null, null, null, null, null, user.id(), user.id(), List.of(), now));
    data.client()
        .forKeyValueEntity(Ids.user(user.id()))
        .method(UserEntity::appendCollection)
        .invoke(new UserEntity.CollectionRef(id, now));
    Records.Collection created = data.collection(id).orElseThrow();
    data.indexCollection(created, null);
    return created;
  }

  // ------------------------------------------------------------------
  // the two refusals that come before the write
  // ------------------------------------------------------------------

  /** SPEC-001 R35 — the same owner already holds this url, under either spelling. */
  public boolean isDuplicate(Records.User owner, String url) {
    if (!owner.preventDuplicateLinks() || url == null) return false;
    List<String> candidates = Urls.duplicateCandidates(url);
    for (Records.Collection collection : data.reachableCollections(owner.id())) {
      if (collection.ownerId() != owner.id()) continue;
      for (LinksView.LinkRow row : data.linkRowsIn(collection.id())) {
        if (row.url().isPresent() && candidates.contains(row.url().get())) return true;
      }
    }
    return false;
  }

  /** SPEC-001 R36, R73 — how many links this account may still hold. */
  public boolean hasPassedLimit(Records.User user, int adding, Instant now) {
    int own = 0;
    for (Records.Collection collection : data.reachableCollections(user.id())) {
      if (collection.ownerId() == user.id()) own += data.linkRowsIn(collection.id()).size();
    }
    Capacity.Account account =
        new Capacity.Account(user.parentSubscriptionId(), null, null, null, user.createdAt());
    return Capacity.hasPassedLimit(account, adding, own, own, 0, config, now);
  }

  // ------------------------------------------------------------------
  // the write
  // ------------------------------------------------------------------

  /** What a caller asked to be saved, before the fetch and the guard have had their say. */
  public record Proposal(
      String name,
      String url,
      String type,
      String description,
      String textContent,
      String image,
      List<String> tagNames,
      Instant importDate) {}

  /**
   * SPEC-001 R37–R40 — the name, the type and the tags a saved link ends up with.
   *
   * @param fetch whether the page is read for its title and content type; an import and an RSS
   *     poll save what the file or the feed said and never fetch.
   */
  public Records.Link create(
      Records.User caller,
      Records.Collection collection,
      Proposal proposal,
      boolean fetch,
      Instant now) {

    String url = proposal.url();
    boolean safe = url == null || fetcher.isSafe(url);
    Fetcher.Page page =
        fetch && url != null && safe ? fetcher.titleAndHeaders(url) : Fetcher.Page.NOTHING;

    // R37 — a given name wins; a nameless link takes the page's title, and the url when the
    // page gave none.
    String name = proposal.name() == null ? "" : proposal.name().trim();
    if (name.isEmpty()) name = page.title() == null ? "" : page.title().trim();
    if (name.isEmpty() && url != null) name = url;

    // R38 — the type is the caller's only when there is no url to read a content type from.
    String type =
        url == null
            ? (proposal.type() == null ? "url" : proposal.type())
            : Urls.typeFromContentType(page.contentType());

    List<Integer> tagIds = new ArrayList<>();
    for (String tagName : proposal.tagNames() == null ? List.<String>of() : proposal.tagNames()) {
      if (tagName == null || tagName.trim().isEmpty()) continue;
      // R40 — the tag belongs to the collection's owner, not to whoever saved the link.
      Records.Tag tag = data.findOrCreateTag(collection.ownerId(), tagName, now);
      if (!tagIds.contains(tag.id())) tagIds.add(tag.id());
    }

    int id = data.nextId("link");
    data.client()
        .forKeyValueEntity(Ids.link(id))
        .method(LinkEntity::create)
        .invoke(
            new LinkEntity.Create(
                id,
                name,
                type,
                proposal.description(),
                collection.id(),
                caller.id(),
                url,
                tagIds,
                proposal.importDate(),
                // R39 — a url the guard refuses is finished before anything can offer it.
                url != null && !safe,
                proposal.image(),
                proposal.textContent(),
                now));
    data.indexLink(id, collection.id());
    data.retag(id, List.of(), tagIds);
    return data.link(id).orElseThrow();
  }
}

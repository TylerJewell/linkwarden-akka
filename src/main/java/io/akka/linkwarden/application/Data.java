package io.akka.linkwarden.application;

import akka.javasdk.client.ComponentClient;
import io.akka.linkwarden.domain.Ids;
import io.akka.linkwarden.domain.Permissions;
import io.akka.linkwarden.domain.Records;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The reads and writes every route makes, in one place.
 *
 * <p>Linkwarden's rules are relational — who may see a link is a fact about the collection that
 * holds it and the membership rows on that collection — and the views here are single tables. The
 * join is therefore done in this class rather than denormalised into the rows: a collection
 * gaining a member would otherwise have to be written into every link it holds, and a permission
 * answer would have as many copies as there are links.
 */
public final class Data {

  private final ComponentClient client;

  public Data(ComponentClient client) {
    this.client = client;
  }

  // ------------------------------------------------------------------
  // identifiers
  // ------------------------------------------------------------------

  public int nextId(String kind) {
    return client.forKeyValueEntity(kind).method(CounterEntity::take).invoke();
  }

  // ------------------------------------------------------------------
  // users
  // ------------------------------------------------------------------

  public Optional<Records.User> user(int userId) {
    try {
      return Optional.of(client.forKeyValueEntity(Ids.user(userId)).method(UserEntity::get).invoke());
    } catch (RuntimeException e) {
      return Optional.empty();
    }
  }

  /**
   * SPEC-001 R12, R16 — read from the directory rather than from a projection.
   *
   * <p>A caller registers and signs in on the next request, and a projection is behind by however
   * long its stream is; the directory is written as part of registering, so it answers about the
   * account that has just been made.
   */
  public Optional<Records.User> userByUsername(String username) {
    if (username == null) return Optional.empty();
    return holder(Ids.usernameHolder(username)).flatMap(this::user);
  }

  public Optional<Records.User> userByEmail(String email) {
    if (email == null) return Optional.empty();
    return holder(Ids.emailHolder(email)).flatMap(this::user);
  }

  private Optional<Integer> holder(String key) {
    int userId = client.forKeyValueEntity(key).method(DirectoryEntity::get).invoke().userId();
    return userId == 0 ? Optional.empty() : Optional.of(userId);
  }

  /** Claims a unique value for an account, answering false when somebody else already holds it. */
  public boolean claim(String key, int userId) {
    return client.forKeyValueEntity(key).method(DirectoryEntity::claim).invoke(userId);
  }

  public void release(String key) {
    client.forKeyValueEntity(key).method(DirectoryEntity::release).invoke();
  }

  /**
   * Every live account, ascending by key.
   *
   * <p>Walked over the key counter rather than read from a projection: the administrator's list is
   * asked for immediately after an account is made, and a row that has not arrived yet reads as an
   * account that does not exist. The counter is written by the registration itself, so the walk
   * sees everything registered before the call.
   */
  public List<Records.User> allUsers() {
    int next = client.forKeyValueEntity("user").method(CounterEntity::peek).invoke();
    List<Records.User> users = new ArrayList<>();
    for (int id = 1; id < next; id++) {
      user(id).ifPresent(users::add);
    }
    return users;
  }

  // ------------------------------------------------------------------
  // collections
  // ------------------------------------------------------------------

  public Optional<Records.Collection> collection(int collectionId) {
    if (collectionId <= 0) return Optional.empty();
    try {
      return Optional.of(
          client
              .forKeyValueEntity(Ids.collection(collectionId))
              .method(CollectionEntity::get)
              .invoke());
    } catch (RuntimeException e) {
      return Optional.empty();
    }
  }

  /** SPEC-001 R23 — every collection the caller owns or is a member of, in identifier order. */
  public List<Records.Collection> reachableCollections(int userId) {
    return idsUnder(Ids.collectionsOf(userId)).stream()
        .sorted()
        .map(this::collection)
        .flatMap(Optional::stream)
        .toList();
  }

  public List<Records.Collection> childrenOf(int collectionId) {
    return idsUnder(Ids.childrenOf(collectionId)).stream()
        .sorted()
        .map(this::collection)
        .flatMap(Optional::stream)
        .toList();
  }

  // ------------------------------------------------------------------
  // the relations a rule walks
  // ------------------------------------------------------------------

  public List<Integer> idsUnder(String key) {
    return client.forKeyValueEntity(key).method(IdSetEntity::get).invoke().ids();
  }

  public void addTo(String key, int id) {
    client.forKeyValueEntity(key).method(IdSetEntity::add).invoke(id);
  }

  public void removeFrom(String key, int id) {
    client.forKeyValueEntity(key).method(IdSetEntity::remove).invoke(id);
  }

  /**
   * Records a collection under everyone who can reach it and under its parent.
   *
   * <p>Called after every write that changes a collection's owner, members or parent, and it
   * writes the whole relation rather than a difference: the membership set is replaced wholesale
   * by R25, so working out what moved would be re-deriving what the caller already knows.
   */
  public void indexCollection(Records.Collection collection, Records.Collection before) {
    if (before != null) {
      if (before.parentId() != null && !java.util.Objects.equals(before.parentId(), collection.parentId())) {
        removeFrom(Ids.childrenOf(before.parentId()), collection.id());
      }
      for (Permissions.Member member : before.members()) {
        if (collection.members().stream().noneMatch(m -> m.userId() == member.userId())) {
          removeFrom(Ids.collectionsOf(member.userId()), collection.id());
        }
      }
    }
    addTo(Ids.collectionsOf(collection.ownerId()), collection.id());
    for (Permissions.Member member : collection.members()) {
      addTo(Ids.collectionsOf(member.userId()), collection.id());
    }
    if (collection.parentId() != null) {
      addTo(Ids.childrenOf(collection.parentId()), collection.id());
    }
  }

  public void unindexCollection(Records.Collection collection) {
    removeFrom(Ids.collectionsOf(collection.ownerId()), collection.id());
    for (Permissions.Member member : collection.members()) {
      removeFrom(Ids.collectionsOf(member.userId()), collection.id());
    }
    if (collection.parentId() != null) {
      removeFrom(Ids.childrenOf(collection.parentId()), collection.id());
    }
  }

  public void indexLink(int linkId, int collectionId) {
    addTo(Ids.linksOf(collectionId), linkId);
  }

  public void unindexLink(int linkId, int collectionId) {
    removeFrom(Ids.linksOf(collectionId), linkId);
  }

  /**
   * SPEC-001 R26 — every descendant, breadth-first.
   *
   * <p>A collection already seen is not walked again: one may be its own parent (question-log row
   * 10) and the walk would otherwise not end.
   */
  public List<Records.Collection> descendantsOf(int collectionId) {
    List<Records.Collection> found = new ArrayList<>();
    Set<Integer> seen = new LinkedHashSet<>();
    seen.add(collectionId);
    List<Integer> frontier = List.of(collectionId);
    while (!frontier.isEmpty()) {
      List<Integer> next = new ArrayList<>();
      for (int id : frontier) {
        for (Records.Collection child : childrenOf(id)) {
          if (!seen.add(child.id())) continue;
          found.add(child);
          next.add(child.id());
        }
      }
      frontier = next;
    }
    return found;
  }

  /**
   * SPEC-001 R23 — asking about a link asks about the collection holding it, with no restriction
   * on who owns that collection; the caller's rights are read from it afterwards.
   */
  public Optional<Permissions.Subject> subjectForLink(int linkId) {
    return link(linkId)
        .flatMap(link -> collection(link.collectionId()))
        .map(Records.Collection::asSubject);
  }

  public Optional<Permissions.Subject> subjectForCollection(int collectionId, int userId) {
    return collection(collectionId)
        .filter(c -> Permissions.canRead(c.asSubject(), userId))
        .map(Records.Collection::asSubject);
  }

  // ------------------------------------------------------------------
  // links
  // ------------------------------------------------------------------

  public Optional<Records.Link> link(int linkId) {
    if (linkId <= 0) return Optional.empty();
    try {
      return Optional.of(
          client.forKeyValueEntity(Ids.link(linkId)).method(LinkEntity::get).invoke());
    } catch (RuntimeException e) {
      return Optional.empty();
    }
  }

  /**
   * Every link in the instance.
   *
   * <p>The one read here that is answered by the projection rather than by a relation: it serves
   * the administrator's counts, which are about the instance as a whole and have no key to look
   * under. A count that is a moment behind is a count of a moving thing.
   */
  /**
   * Every live link in the instance, ascending by key.
   *
   * <p>Walked over the key counter for the same reason {@link #allUsers} is: the administrator's
   * counts are asked for straight after links are made, and a projection that has not caught up
   * reports them as work nobody has asked for.
   */
  public List<Records.Link> allLinks() {
    int next = client.forKeyValueEntity("link").method(CounterEntity::peek).invoke();
    List<Records.Link> links = new ArrayList<>();
    for (int id = 1; id < next; id++) {
      link(id).ifPresent(links::add);
    }
    return links;
  }

  /**
   * Every link a collection holds, newest identifier first.
   *
   * <p>Read from the relation rather than from the view because three rules cascade over it — a
   * collection's delete, its index clearing and the capacity count — and a link created a moment
   * before any of them must not be missed.
   */
  public List<LinksView.LinkRow> linkRowsIn(int collectionId) {
    return idsUnder(Ids.linksOf(collectionId)).stream()
        .sorted(java.util.Comparator.reverseOrder())
        .map(this::link)
        .flatMap(Optional::stream)
        .filter(link -> !link.deleted())
        .map(LinksView::rowOf)
        .toList();
  }

  public List<LinksView.LinkRow> linkRowsAwaitingPreservation() {
    return client.forView().method(LinksView::awaitingPreservation).invoke().links();
  }

  public List<LinksView.LinkRow> linkRowsAwaitingIndexing() {
    return client
        .forView()
        .method(LinksView::awaitingIndexing)
        .invoke(LinksView.currentIndexVersion())
        .links();
  }

  /**
   * The rows of every collection the caller can reach, newest identifier first.
   *
   * <p>Gathered collection by collection through the relation rather than filtered out of the
   * view, so that a link saved a moment ago is in the answer: a caller who saves a link and then
   * lists is the ordinary case, not an unusual one.
   */
  public List<LinksView.LinkRow> reachableLinkRows(int userId) {
    List<LinksView.LinkRow> rows = new ArrayList<>();
    for (Records.Collection collection : reachableCollections(userId)) {
      rows.addAll(linkRowsIn(collection.id()));
    }
    rows.sort(java.util.Comparator.comparingInt(LinksView.LinkRow::id).reversed());
    return List.copyOf(rows);
  }

  /** Every link the caller has pinned, newest identifier first. */
  public List<LinksView.LinkRow> pinnedLinkRows(int userId) {
    return reachableLinkRows(userId).stream()
        .filter(row -> row.pinnedBy().contains(userId))
        .toList();
  }

  public long countLinksIn(int collectionId) {
    return linkRowsIn(collectionId).size();
  }

  // ------------------------------------------------------------------
  // tags
  // ------------------------------------------------------------------

  public Optional<Records.Tag> tag(int tagId) {
    if (tagId <= 0) return Optional.empty();
    try {
      return Optional.of(client.forKeyValueEntity(Ids.tag(tagId)).method(TagEntity::get).invoke());
    } catch (RuntimeException e) {
      return Optional.empty();
    }
  }

  /** SPEC-001 R40 — the pair a tag is unique on, answered about the write that just happened. */
  public Optional<Records.Tag> tagNamed(int ownerId, String name) {
    int id =
        client
            .forKeyValueEntity(Ids.tagNamed(ownerId, name))
            .method(DirectoryEntity::get)
            .invoke()
            .userId();
    return id == 0 ? Optional.empty() : tag(id).filter(tag -> !tag.deleted());
  }

  public List<Records.Tag> tagsOwnedBy(int ownerId) {
    return idsUnder(Ids.tagsOf(ownerId)).stream()
        .map(this::tag)
        .flatMap(Optional::stream)
        .filter(tag -> !tag.deleted())
        .toList();
  }

  /** Every link carrying a tag, which is what a count and a merge both read. */
  public List<Integer> linkIdsWithTag(int tagId) {
    return idsUnder(Ids.linksWithTag(tagId));
  }

  /** Moves a link between the tag relations it belongs to. */
  public void retag(int linkId, List<Integer> before, List<Integer> after) {
    for (int tagId : before) {
      if (!after.contains(tagId)) removeFrom(Ids.linksWithTag(tagId), linkId);
    }
    for (int tagId : after) {
      if (!before.contains(tagId)) addTo(Ids.linksWithTag(tagId), linkId);
    }
  }

  public List<Records.Tag> tagsOf(Records.Link link) {
    return link.tagIds().stream().map(this::tag).flatMap(Optional::stream).toList();
  }

  /** Forgets a tag: its name is free again and its owner no longer lists it. */
  public void unindexTag(Records.Tag tag) {
    removeFrom(Ids.tagsOf(tag.ownerId()), tag.id());
    release(Ids.tagNamed(tag.ownerId(), tag.name()));
  }

  public Map<Integer, Records.Tag> tagsByIdFor(List<LinksView.LinkRow> rows) {
    Map<Integer, Records.Tag> byId = new LinkedHashMap<>();
    for (LinksView.LinkRow row : rows) {
      for (int tagId : row.tagIds()) {
        if (!byId.containsKey(tagId)) tag(tagId).ifPresent(t -> byId.put(tagId, t));
      }
    }
    return byId;
  }

  /**
   * SPEC-001 R40 — a tag is found or created on the pair it is unique on, and the owner is the
   * collection's, not the caller's.
   */
  public Records.Tag findOrCreateTag(int ownerId, String name, java.time.Instant now) {
    String trimmed = name.trim();
    Optional<Records.Tag> existing = tagNamed(ownerId, trimmed);
    if (existing.isPresent()) return existing.get();
    int id = nextId("tag");
    client
        .forKeyValueEntity(Ids.tag(id))
        .method(TagEntity::create)
        .invoke(new TagEntity.Create(id, trimmed, ownerId, false, now));
    client.forKeyValueEntity(Ids.tagNamed(ownerId, trimmed)).method(DirectoryEntity::claim).invoke(id);
    addTo(Ids.tagsOf(ownerId), id);
    return tag(id).orElseThrow();
  }

  // ------------------------------------------------------------------
  // highlights, tokens, feeds
  // ------------------------------------------------------------------

  public Optional<Records.Highlight> highlight(int id) {
    if (id <= 0) return Optional.empty();
    try {
      return Optional.of(
          client.forKeyValueEntity(Ids.highlight(id)).method(HighlightEntity::get).invoke());
    } catch (RuntimeException e) {
      return Optional.empty();
    }
  }

  /** SPEC-001 R70 — one person's marks on one link, including the one just made. */
  public List<Records.Highlight> highlightsOn(int linkId, int userId) {
    return idsUnder(Ids.highlightsOn(linkId, userId)).stream()
        .sorted()
        .map(this::highlight)
        .flatMap(Optional::stream)
        .filter(highlight -> !highlight.deleted())
        .toList();
  }

  public Optional<Records.AccessToken> accessToken(int id) {
    if (id <= 0) return Optional.empty();
    try {
      return Optional.of(
          client.forKeyValueEntity(Ids.accessToken(id)).method(AccessTokenEntity::get).invoke());
    } catch (RuntimeException e) {
      return Optional.empty();
    }
  }

  /**
   * SPEC-001 R14 — the account's tokens that are still live.
   *
   * <p>Read from the relation for the same reason the collections are: a token minted a moment
   * ago is what the answer to minting is built from, and a token revoked a moment ago must stop
   * working on the very next request rather than on the one after the projection catches up.
   */
  public List<Records.AccessToken> liveTokensFor(int userId) {
    return idsUnder(Ids.tokensOf(userId)).stream()
        .map(this::accessToken)
        .flatMap(Optional::stream)
        .filter(token -> !token.revoked())
        .sorted(java.util.Comparator.comparingInt(Records.AccessToken::id))
        .toList();
  }

  /** SPEC-001 R10 — the row a presented token's identifier belongs to. */
  public Optional<Records.AccessToken> tokenWithIdentifier(String jti) {
    if (jti == null) return Optional.empty();
    int id =
        client
            .forKeyValueEntity(Ids.tokenByIdentifier(jti))
            .method(DirectoryEntity::get)
            .invoke()
            .userId();
    return id == 0 ? Optional.empty() : accessToken(id);
  }

  /** Records a token under its account and under its own identifier. */
  public void indexToken(Records.AccessToken token) {
    addTo(Ids.tokensOf(token.userId()), token.id());
    client
        .forKeyValueEntity(Ids.tokenByIdentifier(token.jti()))
        .method(DirectoryEntity::claim)
        .invoke(token.id());
  }

  public Optional<Records.RssSubscription> feed(int id) {
    if (id <= 0) return Optional.empty();
    try {
      return Optional.of(
          client.forKeyValueEntity(Ids.rss(id)).method(RssSubscriptionEntity::get).invoke());
    } catch (RuntimeException e) {
      return Optional.empty();
    }
  }

  /** SPEC-001 R75 — the account's own feeds, counted and named-checked against the write. */
  public List<Records.RssSubscription> feedsOwnedBy(int ownerId) {
    return idsUnder(Ids.feedsOf(ownerId)).stream()
        .sorted()
        .map(this::feed)
        .flatMap(Optional::stream)
        .filter(feed -> !feed.deleted())
        .toList();
  }

  public ComponentClient client() {
    return client;
  }
}

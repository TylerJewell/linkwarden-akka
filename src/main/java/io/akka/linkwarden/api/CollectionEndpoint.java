package io.akka.linkwarden.api;

import akka.http.javadsl.model.HttpResponse;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Delete;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Post;
import akka.javasdk.annotations.http.Put;
import com.fasterxml.jackson.databind.JsonNode;
import io.akka.linkwarden.application.CollectionEntity;
import io.akka.linkwarden.application.Data;
import io.akka.linkwarden.application.FileStore;
import io.akka.linkwarden.application.LinkEntity;
import io.akka.linkwarden.application.LinksView;
import io.akka.linkwarden.application.UserEntity;
import io.akka.linkwarden.domain.Config;
import io.akka.linkwarden.domain.Ids;
import io.akka.linkwarden.domain.Permissions;
import io.akka.linkwarden.domain.Records;
import io.akka.linkwarden.domain.Validation;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Collections, their members, and what a delete reaches. SPEC-001 R23-R32. */
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
@HttpEndpoint("/api/v1")
public class CollectionEndpoint extends Surface {

  private final FileStore files;

  public CollectionEndpoint(Data data, Config config, FileStore files) {
    super(data, config);
    this.files = files;
  }

  // ------------------------------------------------------------------
  // reading
  // ------------------------------------------------------------------

  @Get("/collections")
  public HttpResponse list() {
    Caller.Result result = signedIn();
    if (result.refused()) return result.refusal();

    Records.User user = result.user();
    List<Integer> order = user.collectionOrder() == null ? List.of() : user.collectionOrder();
    List<Records.Collection> collections = new ArrayList<>(data.reachableCollections(user.id()));
    // R29 - the caller's own order first; a collection the order does not name falls behind
    // every one it does, and then sorts by creation time and id.
    collections.sort(
        Comparator.comparingInt((Records.Collection c) -> position(order, c.id()))
            .thenComparing(Records.Collection::createdAt)
            .thenComparingInt(Records.Collection::id));
    return Answers.wrapped(200, collections.stream().map(this::listed).toList());
  }

  private static int position(List<Integer> order, int id) {
    int at = order.indexOf(id);
    return at < 0 ? Integer.MAX_VALUE : at;
  }

  /**
   * A collection as every route but the listing answers it.
   *
   * @param withMemberIdentifiers true on the update route, which selects each member's own
   *     identifier as well as the three fields the read route shows
   */
  Map<String, Object> shape(Records.Collection collection, boolean withMemberIdentifiers) {
    List<Map<String, Object>> members = new ArrayList<>();
    for (Permissions.Member member : collection.members()) {
      members.add(Shapes.member(member, data.user(member.userId()).orElse(null),
          collection.id(), withMemberIdentifiers));
    }
    return Shapes.collection(collection, data.countLinksIn(collection.id()), members);
  }

  Map<String, Object> shape(Records.Collection collection) {
    return shape(collection, false);
  }

  /** The listing carries each collection's parent beside it; no other route does. */
  Map<String, Object> listed(Records.Collection collection) {
    Map<String, Object> out = shape(collection);
    Map<String, Object> parent = null;
    if (collection.parentId() != null) {
      Optional<Records.Collection> found = data.collection(collection.parentId());
      if (found.isPresent()) {
        parent = new LinkedHashMap<>();
        parent.put("id", found.get().id());
        parent.put("name", found.get().name());
      }
    }
    out.put("parent", parent);
    return out;
  }

  @Get("/collections/{id}")
  public HttpResponse read(int id) {
    Caller.Result result = signedIn();
    if (result.refused()) return result.refusal();

    Optional<Records.Collection> found =
        data.collection(id).filter(c -> Permissions.canRead(c.asSubject(), result.user().id()));
    // A collection the caller cannot reach and one that does not exist are the same answer:
    // a 200 carrying nothing (question-log row 9).
    return Answers.wrapped(200, found.map(this::shape).orElse(null));
  }

  // ------------------------------------------------------------------
  // creating
  // ------------------------------------------------------------------

  @Post("/collections")
  public HttpResponse create(JsonNode body) {
    Caller.Result result = signedIn();
    if (result.refused()) return result.refusal();
    if (config.demoMode()) return Answers.demoRefusal();

    String name = Bodies.text(body, "name");
    Optional<Validation.Issue> issue =
        Validation.first(
            Validation.requiredString("name", name, 1, 254),
            Validation.optionalString("description", Bodies.text(body, "description"), 0, 254),
            Validation.optionalString("color", Bodies.text(body, "color"), 0, 50),
            Validation.optionalString("icon", Bodies.text(body, "icon"), 0, 50),
            Validation.optionalString("iconWeight", Bodies.text(body, "iconWeight"), 0, 50));
    if (issue.isPresent()) return Answers.issue(issue.get());

    Integer parentId = Bodies.number(Bodies.child(body, "parent"), "id");
    if (parentId == null) parentId = Bodies.number(body, "parentId");

    Records.User user = result.user();
    int ownerId = user.id();
    List<Permissions.Member> members = List.of();

    if (parentId != null) {
      Optional<Records.Collection> parent = data.collection(parentId);
      if (parent.isEmpty()
          || !Permissions.canCreateSubCollection(parent.get().asSubject(), user.id())) {
        return Answers.wrapped(403, "You are not authorized to create a sub-collection here.");
      }
      // R27-R28 - the new collection belongs to the owner at the top of the chain, and inherits
      // every right anyone holds anywhere along it.
      Permissions.RootAndMembers walked =
          Permissions.walk(
              parentId,
              id -> data.collection(id).map(Records.Collection::asSubject).orElse(null),
              id -> data.collection(id).map(Records.Collection::parentId).orElse(null));
      ownerId = walked.rootOwnerId() == null ? user.id() : walked.rootOwnerId();
      members = Permissions.uniqueMembers(walked.members(), ownerId);
      if (ownerId != user.id() && members.stream().noneMatch(m -> m.userId() == user.id())) {
        List<Permissions.Member> widened = new ArrayList<>(members);
        widened.add(Permissions.Member.full(user.id()));
        members = List.copyOf(widened);
      }
    }

    Instant now = Instant.now();
    int id = data.nextId("collection");
    data.client()
        .forKeyValueEntity(Ids.collection(id))
        .method(CollectionEntity::create)
        .invoke(
            new CollectionEntity.Create(
                id,
                name.trim(),
                Bodies.text(body, "description"),
                Bodies.text(body, "icon"),
                Bodies.text(body, "iconWeight"),
                Bodies.text(body, "color"),
                parentId,
                ownerId,
                user.id(),
                members,
                now));
    // R30 - the caller's own order gains the new collection, and the two archive folders exist
    // before anything is written into them.
    data.client()
        .forKeyValueEntity(Ids.user(user.id()))
        .method(UserEntity::appendCollection)
        .invoke(new UserEntity.CollectionRef(id, now));
    files.createFolder("archives/" + id);
    files.createFolder("archives/preview/" + id);

    Records.Collection created = data.collection(id).orElseThrow();
    data.indexCollection(created, null);
    return Answers.wrapped(200, shape(created));
  }

  // ------------------------------------------------------------------
  // updating
  // ------------------------------------------------------------------

  @Put("/collections/{id}")
  public HttpResponse update(int id, JsonNode body) {
    Caller.Result result = signedIn();
    if (result.refused()) return result.refusal();
    if (config.demoMode()) return Answers.demoRefusal();
    if (id == 0) return Answers.wrapped(401, "Please choose a valid collection.");

    Optional<Records.Collection> found = data.collection(id);
    if (found.isEmpty() || found.get().ownerId() != result.user().id()) {
      return Answers.wrapped(401, "Collection is not accessible.");
    }
    Records.Collection collection = found.get();

    String name = Bodies.text(body, "name");
    Optional<Validation.Issue> issue =
        Validation.first(
            Validation.requiredString("name", name, 1, 254),
            Validation.optionalString("description", Bodies.text(body, "description"), 0, 254));
    if (issue.isPresent()) return Answers.issue(issue.get());

    Integer parentId = null;
    if (Bodies.has(body, "parentId")) {
      JsonNode node = body.get("parentId");
      if (node.isTextual() && node.asText().equals("root")) {
        parentId = -1;
      } else if (node.isNumber()) {
        int proposed = node.asInt();
        if (collection.parentId() != null && collection.parentId() == proposed) {
          return Answers.wrapped(403, "You cannot make a collection a sub-collection of itself.");
        }
        Optional<Records.Collection> parent = data.collection(proposed);
        if (parent.isEmpty() || parent.get().ownerId() != result.user().id()) {
          return Answers.wrapped(403, "You are not authorized to create a sub-collection here.");
        }
        parentId = proposed;
      }
    }

    List<Permissions.Member> members =
        Permissions.uniqueMembers(readMembers(body), collection.ownerId());

    Instant now = Instant.now();
    Records.Collection updated =
        data.client()
            .forKeyValueEntity(Ids.collection(id))
            .method(CollectionEntity::update)
            .invoke(
                new CollectionEntity.Update(
                    name.trim(),
                    Bodies.text(body, "description"),
                    Bodies.text(body, "icon"),
                    Bodies.text(body, "iconWeight"),
                    Bodies.text(body, "color"),
                    Bodies.flag(body, "isPublic"),
                    parentId,
                    members,
                    now));

    data.indexCollection(updated, collection);
    if (Bodies.isOn(body, "propagateToSubcollections")) propagate(id, members, now);
    clearIndexOf(id, now);
    return Answers.wrapped(200, shape(updated, true));
  }

  private List<Permissions.Member> readMembers(JsonNode body) {
    List<Permissions.Member> members = new ArrayList<>();
    for (JsonNode node : Bodies.array(body, "members")) {
      Integer userId = Bodies.number(node, "userId");
      if (userId == null) userId = Bodies.number(Bodies.child(node, "user"), "id");
      if (userId == null) continue;
      members.add(
          new Permissions.Member(
              userId,
              Bodies.isOn(node, "canCreate"),
              Bodies.isOn(node, "canUpdate"),
              Bodies.isOn(node, "canDelete")));
    }
    return members;
  }

  /** R26 - the same set written to every descendant, each minus its own owner. */
  private void propagate(int collectionId, List<Permissions.Member> members, Instant now) {
    for (Records.Collection descendant : data.descendantsOf(collectionId)) {
      data.client()
          .forKeyValueEntity(Ids.collection(descendant.id()))
          .method(CollectionEntity::setMembers)
          .invoke(
              new CollectionEntity.SetMembers(
                  Permissions.uniqueMembers(members, descendant.ownerId()), now));
      data.collection(descendant.id())
          .ifPresent(after -> data.indexCollection(after, descendant));
    }
  }

  private void clearIndexOf(int collectionId, Instant now) {
    for (LinksView.LinkRow row : data.linkRowsIn(collectionId)) {
      data.client()
          .forKeyValueEntity(Ids.link(row.id()))
          .method(LinkEntity::setIndexVersion)
          .invoke(new LinkEntity.SetIndexVersion(null, now));
    }
  }

  // ------------------------------------------------------------------
  // deleting
  // ------------------------------------------------------------------

  @Delete("/collections/{id}")
  public HttpResponse delete(int id) {
    Caller.Result result = signedIn();
    if (result.refused()) return result.refusal();
    if (config.demoMode()) return Answers.demoRefusal();
    if (id == 0) return Answers.wrapped(401, "Please choose a valid collection.");

    Optional<Records.Collection> found = data.collection(id);
    if (found.isEmpty()) return Answers.wrapped(401, "Collection is not accessible.");
    Records.Collection collection = found.get();
    int userId = result.user().id();
    Instant now = Instant.now();

    if (collection.ownerId() != userId) {
      if (!Permissions.isMember(collection.asSubject(), userId)) {
        return Answers.wrapped(401, "Collection is not accessible.");
      }
      // R32 - a member's delete reaches only their own membership.
      data.client()
          .forKeyValueEntity(Ids.collection(id))
          .method(CollectionEntity::removeMember)
          .invoke(userId);
      data.client()
          .forKeyValueEntity(Ids.user(userId))
          .method(UserEntity::removeCollection)
          .invoke(new UserEntity.CollectionRef(id, now));
      data.removeFrom(Ids.collectionsOf(userId), id);
      clearIndexOf(id, now);
      // The answer is the membership row that went, which is what the interface removes.
      Map<String, Object> gone = new LinkedHashMap<>();
      Permissions.Member leaving =
          collection.members().stream().filter(m -> m.userId() == userId).findFirst()
              .orElse(new Permissions.Member(userId, false, false, false));
      gone.put("userId", leaving.userId());
      gone.put("collectionId", id);
      gone.put("canCreate", leaving.canCreate());
      gone.put("canUpdate", leaving.canUpdate());
      gone.put("canDelete", leaving.canDelete());
      return Answers.wrapped(200, gone);
    }

    List<Records.Collection> descendants = new ArrayList<>(data.descendantsOf(id));
    // Deepest first, so no collection is deleted while one of its own children still names it.
    for (int at = descendants.size() - 1; at >= 0; at--) removeWholly(descendants.get(at), now);
    removeWholly(collection, now);
    return Answers.wrapped(200, Shapes.shortCollection(collection));
  }

  private void removeWholly(Records.Collection collection, Instant now) {
    for (LinksView.LinkRow row : data.linkRowsIn(collection.id())) {
      files.removeLinkFiles(collection.id(), row.id());
      data.client().forKeyValueEntity(Ids.link(row.id())).method(LinkEntity::delete).invoke(now);
      data.unindexLink(row.id(), collection.id());
      data.retag(row.id(), row.tagIds(), List.of());
    }
    files.removeFolder("archives/" + collection.id());
    files.removeFolder("archives/preview/" + collection.id());
    for (Records.User user : data.allUsers()) {
      if (user.collectionOrder() != null && user.collectionOrder().contains(collection.id())) {
        data.client()
            .forKeyValueEntity(Ids.user(user.id()))
            .method(UserEntity::removeCollection)
            .invoke(new UserEntity.CollectionRef(collection.id(), now));
      }
    }
    data.client()
        .forKeyValueEntity(Ids.collection(collection.id()))
        .method(CollectionEntity::delete)
        .invoke(now);
    data.unindexCollection(collection);
  }
}

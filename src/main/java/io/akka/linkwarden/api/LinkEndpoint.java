package io.akka.linkwarden.api;

import akka.http.javadsl.model.HttpResponse;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Delete;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Post;
import akka.javasdk.annotations.http.Put;
import com.fasterxml.jackson.databind.JsonNode;
import io.akka.linkwarden.application.Data;
import io.akka.linkwarden.application.FileStore;
import io.akka.linkwarden.application.LinkEntity;
import io.akka.linkwarden.application.LinkWriter;
import io.akka.linkwarden.application.LinksView;
import io.akka.linkwarden.domain.Config;
import io.akka.linkwarden.domain.Ids;
import io.akka.linkwarden.domain.Permissions;
import io.akka.linkwarden.domain.Records;
import io.akka.linkwarden.domain.Validation;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Links: saving, listing, moving, pinning, archiving again and deleting. SPEC-001 R33–R47, R54–R55. */
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
@HttpEndpoint("/api/v1")
public class LinkEndpoint extends Surface {

  private final LinkWriter writer;
  private final FileStore files;

  public LinkEndpoint(Data data, Config config, LinkWriter writer, FileStore files) {
    super(data, config);
    this.writer = writer;
    this.files = files;
  }

  // ------------------------------------------------------------------
  // reading
  // ------------------------------------------------------------------

  /** SPEC-001 R41 — a cursor page over the collections the caller can reach, filtered. */
  @Get("/links")
  public HttpResponse list() {
    Caller.Result result = signedIn();
    if (result.refused()) return result.refusal();

    Map<String, Object> page =
        LinkSearch.run(
            data,
            config,
            result.user(),
            new LinkSearch.Request(
                queryNumber("cursor"),
                queryNumber("collectionId"),
                queryNumber("tagId"),
                queryFlag("pinnedOnly"),
                query("searchQueryString").orElse(null),
                queryNumber("sort") == null ? 0 : queryNumber("sort"),
                false));
    // The listing route answers the page itself; only the search route wraps it beside a cursor.
    return Answers.wrapped(200, page.get("links"));
  }

  Map<String, Object> shapeRow(LinksView.LinkRow row) {
    return data.link(row.id()).map(this::shape).orElse(new LinkedHashMap<>());
  }

  Map<String, Object> shape(Records.Link link) {
    return Shapes.link(
        link,
        data.collection(link.collectionId()).orElse(null),
        data.tagsOf(link),
        link.pinnedBy(),
        false);
  }

  @Get("/links/{id}")
  public HttpResponse read(int id) {
    Caller.Result result = signedIn();
    if (result.refused()) return result.refusal();
    if (id == 0) return Answers.wrapped(401, "Please choose a valid link.");

    Optional<Records.Link> found = data.link(id);
    if (found.isEmpty()) return Answers.wrapped(401, "Collection is not accessible.");
    Optional<Permissions.Subject> subject = data.subjectForLink(id);
    if (subject.isEmpty() || !Permissions.canRead(subject.get(), result.user().id())) {
      return Answers.wrapped(401, "Collection is not accessible.");
    }
    return Answers.wrapped(200, shape(found.get()));
  }

  /** SPEC-001 R70's neighbour — the caller's own marks on one link. */
  @Get("/links/{id}/highlights")
  public HttpResponse highlightsOn(int id) {
    Caller.Result result = signedIn();
    if (result.refused()) return result.refusal();
    Optional<Permissions.Subject> subject = data.subjectForLink(id);
    if (subject.isEmpty() || !Permissions.canRead(subject.get(), result.user().id())) {
      return Answers.wrapped(401, "Collection is not accessible.");
    }
    return Answers.wrapped(
        200,
        data.highlightsOn(id, result.user().id()).stream().map(Shapes::highlight).toList());
  }

  // ------------------------------------------------------------------
  // saving
  // ------------------------------------------------------------------

  @Post("/links")
  public HttpResponse create(JsonNode body) {
    Caller.Result result = signedIn();
    if (result.refused()) return result.refusal();
    if (config.demoMode()) return Answers.demoRefusal();

    Optional<Validation.Issue> issue = validateProposal(body);
    if (issue.isPresent()) return Answers.issue(issue.get());

    Records.User user = result.user();
    Instant now = Instant.now();
    JsonNode collectionNode = Bodies.child(body, "collection");
    LinkWriter.Resolution where =
        writer.resolveCollection(
            user,
            Bodies.number(collectionNode, "id"),
            Bodies.text(collectionNode, "name"),
            now);
    if (where.refused()) return Answers.wrapped(where.status(), where.message());

    Records.User owner = data.user(where.collection().ownerId()).orElse(user);
    if (writer.isDuplicate(owner, Bodies.text(body, "url"))) {
      return Answers.wrapped(409, "Link already exists");
    }
    if (writer.hasPassedLimit(owner, 1, now)) {
      return Answers.wrapped(
          400, "Your subscription has reached the maximum number of links allowed.");
    }

    Records.Link link =
        writer.create(
            user,
            where.collection(),
            new LinkWriter.Proposal(
                Bodies.text(body, "name"),
                Bodies.text(body, "url"),
                Bodies.text(body, "type"),
                Bodies.text(body, "description"),
                null,
                null,
                tagNames(body),
                null),
            true,
            now);
    // A link answered by the saving route carries no pinnedBy; the interface reads its absence.
    return Answers.wrapped(
        200,
        Shapes.link(
            link, data.collection(link.collectionId()).orElse(null), data.tagsOf(link), null, false));
  }

  private static List<String> tagNames(JsonNode body) {
    List<String> names = new ArrayList<>();
    for (JsonNode node : Bodies.array(body, "tags")) {
      String name = Bodies.text(node, "name");
      if (name != null) names.add(name);
    }
    return names;
  }

  /** SPEC-001 R33 — the schema, in the order the original declares its fields. */
  private static Optional<Validation.Issue> validateProposal(JsonNode body) {
    String type = Bodies.text(body, "type");
    Optional<Validation.Issue> issue =
        Validation.first(
            type == null
                ? Optional.<Validation.Issue>empty()
                : Validation.oneOf("type", type, List.of("url", "pdf", "image")),
            Validation.optionalRawString("url", Bodies.text(body, "url"), 2048),
            Validation.optionalRawString("name", Bodies.text(body, "name"), 2048),
            Validation.optionalRawString("description", Bodies.text(body, "description"), 2048));
    if (issue.isPresent()) return issue;
    String url = Bodies.text(body, "url");
    if (url != null && !url.isEmpty() && !Validation.isParseableUrl(url)) {
      return Optional.of(Validation.invalidUrl("url"));
    }
    for (JsonNode node : Bodies.array(body, "tags")) {
      Optional<Validation.Issue> tagIssue =
          Validation.optionalString("tags, name", Bodies.text(node, "name"), 1, 50);
      if (tagIssue.isPresent()) return tagIssue;
    }
    return Optional.empty();
  }

  // ------------------------------------------------------------------
  // updating
  // ------------------------------------------------------------------

  @Put("/links/{id}")
  public HttpResponse update(int id, JsonNode body) {
    Caller.Result result = signedIn();
    if (result.refused()) return result.refusal();
    if (config.demoMode()) return Answers.demoRefusal();
    return applyUpdate(result.user(), id, body, true, Instant.now());
  }

  /**
   * SPEC-001 R42–R45 — one link's update, which the bulk route applies to each of its own.
   *
   * @param replaceTags true for the single-link route, which replaces the previous set; the bulk
   *     route adds to it unless it asked for the previous ones to go.
   */
  HttpResponse applyUpdate(
      Records.User user, int id, JsonNode body, boolean replaceTags, Instant now) {
    if (id == 0) return Answers.wrapped(401, "Please choose a valid link.");
    Optional<Records.Link> found = data.link(id);
    if (found.isEmpty()) return Answers.wrapped(401, "Collection is not accessible.");
    Records.Link link = found.get();

    Optional<Records.Collection> holder = data.collection(link.collectionId());
    Permissions.Subject subject = holder.map(Records.Collection::asSubject).orElse(null);
    if (subject == null || !Permissions.canRead(subject, user.id())) {
      return Answers.wrapped(401, "Collection is not accessible.");
    }

    // R42 — a member pinning does only that, and is answered before every other check.
    boolean owner = Permissions.isOwner(subject, user.id());
    if (!owner && body != null && body.has("pinnedBy")) {
      boolean pinning =
          Bodies.array(body, "pinnedBy").stream()
              .anyMatch(node -> Bodies.number(node, "id") != null
                  && Bodies.number(node, "id") == user.id());
      Records.Link pinned =
          data.client()
              .forKeyValueEntity(Ids.link(id))
              .method(LinkEntity::pin)
              .invoke(new LinkEntity.Pin(user.id(), pinning, now));
      return Answers.wrapped(200, shape(pinned));
    }

    JsonNode collectionNode = Bodies.child(body, "collection");
    Integer targetCollectionId = Bodies.number(collectionNode, "id");
    if (targetCollectionId != null && targetCollectionId != link.collectionId()) {
      Optional<Records.Collection> target = data.collection(targetCollectionId);
      if (target.isEmpty()) return Answers.wrapped(401, "Collection is not accessible.");
      if (!owner || target.get().ownerId() != user.id()) {
        return Answers.wrapped(
            401, "You can't move a link to/from a collection you don't own.");
      }
    }
    if (!owner && !Permissions.canUpdate(subject, user.id())) {
      return Answers.wrapped(401, "Collection is not accessible.");
    }

    // The address is compared to the one already stored, and leaving it out of the body is a
    // change to nothing — which is refused the same way an unparseable one is. A caller updating
    // a link that has an address therefore has to send it, whether or not it is moving.
    String url = Bodies.text(body, "url");
    boolean urlChanged = !java.util.Objects.equals(url, link.url());
    if (urlChanged && (url == null || !Validation.isParseableUrl(url))) {
      return Answers.wrapped(401, "Invalid URL.");
    }

    List<Integer> tagIds = null;
    if (body != null && body.has("tags")) {
      // R44 — de-duplicated by name, first kept.
      Set<String> seen = new LinkedHashSet<>();
      List<Integer> resolved = new ArrayList<>();
      if (!replaceTags) resolved.addAll(link.tagIds());
      int tagOwner = holder.map(Records.Collection::ownerId).orElse(user.id());
      for (JsonNode node : Bodies.array(body, "tags")) {
        String name = Bodies.text(node, "name");
        if (name == null || name.trim().isEmpty()) continue;
        if (!seen.add(name.trim())) continue;
        Records.Tag tag = data.findOrCreateTag(tagOwner, name, now);
        if (!resolved.contains(tag.id())) resolved.add(tag.id());
      }
      tagIds = resolved;
    }

    // R43 — a url that moved takes the whole preservation record with it, and the files too.
    if (urlChanged) files.removeLinkFiles(link.collectionId(), link.id());
    Records.Link updated =
        data.client()
            .forKeyValueEntity(Ids.link(id))
            .method(LinkEntity::update)
            .invoke(
                new LinkEntity.Update(
                    Bodies.text(body, "name"),
                    url,
                    Bodies.text(body, "description"),
                    Bodies.text(body, "icon"),
                    Bodies.text(body, "iconWeight"),
                    Bodies.text(body, "color"),
                    targetCollectionId,
                    tagIds,
                    urlChanged,
                    now));

    if (tagIds != null) data.retag(id, link.tagIds(), tagIds);

    // R42's second half — the owner pins through this path rather than through the one above,
    // and a pinnedBy naming anybody else unpins.
    if (body != null && body.has("pinnedBy")) {
      boolean pinning =
          Bodies.array(body, "pinnedBy").stream()
              .anyMatch(node -> Bodies.number(node, "id") != null
                  && Bodies.number(node, "id") == user.id());
      updated =
          data.client()
              .forKeyValueEntity(Ids.link(id))
              .method(LinkEntity::pin)
              .invoke(new LinkEntity.Pin(user.id(), pinning, now));
    }

    boolean bodyNamesTheOwner =
        Bodies.number(collectionNode, "ownerId") != null
            && Bodies.number(collectionNode, "ownerId") == user.id()
            && holder.map(Records.Collection::ownerId).orElse(-1) == user.id();

    // R45 — a moved link's files follow it, and so does the collection's own record of it.
    if (targetCollectionId != null && targetCollectionId != link.collectionId()) {
      files.moveLinkFiles(link.id(), link.collectionId(), targetCollectionId);
      data.unindexLink(link.id(), link.collectionId());
      data.indexLink(link.id(), targetCollectionId);
    }
    return Answers.wrapped(
        200,
        Shapes.link(
            updated,
            data.collection(updated.collectionId()).orElse(null),
            data.tagsOf(updated),
            bodyNamesTheOwner ? updated.pinnedBy() : null,
            false));
  }

  /**
   * SPEC-001 R47 — each named link in turn, and a partial failure keeps what already worked.
   *
   * <p>What each link is updated with is its own fields with the body's changes laid over
   * them, not the body alone: a caller renaming twenty links does not send twenty addresses,
   * and R43 refuses an update whose address is missing. An identifier naming no link is
   * skipped rather than failed, so the answer is about the links that are there.
   */
  @Put("/links")
  public HttpResponse bulkUpdate(JsonNode body) {
    Caller.Result result = signedIn();
    if (result.refused()) return result.refusal();
    if (config.demoMode()) return Answers.demoRefusal();

    List<Integer> ids = new ArrayList<>();
    for (JsonNode node : Bodies.array(body, "links")) {
      Integer id = Bodies.number(node, "id");
      if (id != null) ids.add(id);
    }
    JsonNode changes = Bodies.child(body, "newData");
    boolean removePrevious = Bodies.isOn(body, "removePreviousTags");
    Instant now = Instant.now();

    boolean allSucceeded = true;
    for (int id : ids) {
      Optional<Records.Link> found = data.link(id);
      if (found.isEmpty()) continue;
      HttpResponse answer =
          applyUpdate(result.user(), id, overlaid(found.get(), changes), removePrevious, now);
      if (answer.status().intValue() != 200) allSucceeded = false;
    }
    return allSucceeded
        ? Answers.wrapped(200, "All links updated successfully")
        : Answers.wrapped(400, "Some links failed to update");
  }

  /** A link's own fields, with whatever the bulk body changes laid over them. */
  private JsonNode overlaid(Records.Link link, JsonNode changes) {
    com.fasterxml.jackson.databind.node.ObjectNode merged = Answers.mapper().createObjectNode();
    merged.put("name", link.name());
    merged.put("url", link.url());
    merged.put("description", link.description());
    merged.put("icon", link.icon());
    merged.put("iconWeight", link.iconWeight());
    merged.put("color", link.color());
    com.fasterxml.jackson.databind.node.ObjectNode collection = merged.putObject("collection");
    collection.put("id", link.collectionId());
    if (changes == null) return merged;
    if (changes.has("tags")) merged.set("tags", changes.get("tags"));
    Integer target = Bodies.number(changes, "collectionId");
    if (target == null) target = Bodies.number(Bodies.child(changes, "collection"), "id");
    if (target != null) collection.put("id", target);
    for (String field : List.of("name", "description", "icon", "iconWeight", "color")) {
      if (changes.has(field)) merged.set(field, changes.get(field));
    }
    return merged;
  }

  // ------------------------------------------------------------------
  // archiving again
  // ------------------------------------------------------------------

  /** SPEC-001 R54 — a link asked to be preserved again, and the one 200 that carries a refusal. */
  @Put("/links/{id}/archive")
  public HttpResponse archiveAgain(int id) {
    Caller.Result result = signedIn();
    if (result.refused()) return result.refusal();

    // The link is looked up before the caller's rights and before the demo refusal, so a link
    // nobody has is a 404 whoever asks and whatever the deployment.
    Optional<Records.Link> found = data.link(id);
    if (found.isEmpty()) return Answers.wrapped(404, "Link not found.");
    Optional<Permissions.Subject> subject = data.subjectForLink(id);
    if (subject.isEmpty()
        || !(Permissions.isOwner(subject.get(), result.user().id())
            || Permissions.canUpdate(subject.get(), result.user().id()))) {
      return Answers.wrapped(401, "Permission denied.");
    }
    if (config.demoMode()) return Answers.demoRefusal();
    Records.Link link = found.get();
    if (link.url() == null || !Validation.isParseableUrl(link.url())) {
      // A 200 rather than an error: the original answers this the same way it answers success.
      return Answers.wrapped(200, "Invalid URL.");
    }
    Instant now = Instant.now();
    files.removeLinkFiles(link.collectionId(), link.id());
    data.client().forKeyValueEntity(Ids.link(id)).method(LinkEntity::rePreserve).invoke(now);
    return Answers.wrapped(200, "Link is being archived.");
  }

  /** SPEC-001 R55 — the answer is sent before the work, and an empty selection is the refusal. */
  @Delete("/links/archive")
  public HttpResponse deleteArchives(JsonNode body) {
    Caller.Result result = signedIn();
    if (result.refused()) return result.refusal();
    if (config.demoMode()) return Answers.demoRefusal();

    List<Integer> ids = Bodies.integers(body, "linkIds");
    if (ids == null) ids = List.of();
    int userId = result.user().id();
    List<Records.Link> selected = new ArrayList<>();
    for (int id : ids) {
      Optional<Records.Link> link = data.link(id);
      Optional<Permissions.Subject> subject = data.subjectForLink(id);
      if (link.isEmpty() || subject.isEmpty()) continue;
      if (Permissions.isOwner(subject.get(), userId) || Permissions.canDelete(subject.get(), userId)) {
        selected.add(link.get());
      }
    }
    if (selected.isEmpty()) return Answers.wrapped(401, "Permission denied.");

    Instant now = Instant.now();
    for (Records.Link link : selected) {
      files.removeLinkFiles(link.collectionId(), link.id());
      data.client()
          .forKeyValueEntity(Ids.link(link.id()))
          .method(LinkEntity::rePreserve)
          .invoke(now);
    }
    return Answers.wrapped(200, "Success.");
  }

  // ------------------------------------------------------------------
  // deleting
  // ------------------------------------------------------------------

  @Delete("/links/{id}")
  public HttpResponse delete(int id) {
    Caller.Result result = signedIn();
    if (result.refused()) return result.refusal();
    if (config.demoMode()) return Answers.demoRefusal();
    if (id == 0) return Answers.wrapped(401, "Please choose a valid link.");

    Optional<Records.Link> found = data.link(id);
    Optional<Permissions.Subject> subject = data.subjectForLink(id);
    if (found.isEmpty()
        || subject.isEmpty()
        || !(Permissions.isOwner(subject.get(), result.user().id())
            || Permissions.canDelete(subject.get(), result.user().id()))) {
      return Answers.wrapped(401, "Collection is not accessible.");
    }
    // The row as it stood, which is what a delete hands back.
    Map<String, Object> deleted = Shapes.bareLink(found.get());
    removeOne(found.get(), Instant.now());
    return Answers.wrapped(200, deleted);
  }

  /** SPEC-001 R46 — every id is checked before any of them is removed. */
  @Delete("/links")
  public HttpResponse bulkDelete(JsonNode body) {
    Caller.Result result = signedIn();
    if (result.refused()) return result.refusal();
    if (config.demoMode()) return Answers.demoRefusal();

    List<Integer> ids = Bodies.integers(body, "linkIds");
    if (ids == null || ids.isEmpty()) return Answers.wrapped(401, "Please choose valid links.");
    int userId = result.user().id();
    List<Records.Link> selected = new ArrayList<>();
    for (int id : ids) {
      Optional<Records.Link> link = data.link(id);
      Optional<Permissions.Subject> subject = data.subjectForLink(id);
      if (link.isEmpty()
          || subject.isEmpty()
          || !(Permissions.isOwner(subject.get(), userId)
              || Permissions.canDelete(subject.get(), userId))) {
        return Answers.wrapped(401, "Collection is not accessible.");
      }
      selected.add(link.get());
    }
    Instant now = Instant.now();
    for (Records.Link link : selected) removeOne(link, now);
    Map<String, Object> count = new LinkedHashMap<>();
    count.put("count", selected.size());
    return Answers.wrapped(200, count);
  }

  private void removeOne(Records.Link link, Instant now) {
    files.removeLinkFiles(link.collectionId(), link.id());
    data.client().forKeyValueEntity(Ids.link(link.id())).method(LinkEntity::delete).invoke(now);
    data.unindexLink(link.id(), link.collectionId());
    data.retag(link.id(), link.tagIds(), List.of());
  }
}

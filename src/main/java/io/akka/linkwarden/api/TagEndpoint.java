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
import io.akka.linkwarden.application.LinkEntity;
import io.akka.linkwarden.application.LinksView;
import io.akka.linkwarden.application.TagEntity;
import io.akka.linkwarden.domain.Config;
import io.akka.linkwarden.domain.Ids;
import io.akka.linkwarden.domain.Records;
import io.akka.linkwarden.domain.Validation;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Tags: listing, upserting, renaming, merging and deleting. SPEC-001 R66–R69. */
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
@HttpEndpoint("/api/v1")
public class TagEndpoint extends Surface {

  public TagEndpoint(Data data, Config config) {
    super(data, config);
  }

  // ------------------------------------------------------------------
  // reading
  // ------------------------------------------------------------------

  /**
   * SPEC-001 R66 — one of two questions under one route.
   *
   * <p>Asked about a caller it pages and answers a cursor; asked about a collection it answers
   * every tag on that collection's links in one go and no cursor at all, which is a different
   * shape rather than the same shape with a null in it.
   */
  @Get("/tags")
  public HttpResponse list() {
    Caller.Result result = signedIn();
    if (result.refused()) return result.refusal();

    Integer collectionId = queryNumber("collectionId");
    String search = query("search").map(String::trim).filter(s -> !s.isEmpty()).orElse(null);

    if (collectionId != null) {
      List<Records.Tag> tags = new ArrayList<>();
      Set<Integer> seen = new LinkedHashSet<>();
      for (LinksView.LinkRow row : data.linkRowsIn(collectionId)) {
        for (int tagId : row.tagIds()) {
          if (!seen.add(tagId)) continue;
          data.tag(tagId).ifPresent(tags::add);
        }
      }
      if (search != null) tags.removeIf(tag -> !containsIgnoringCase(tag.name(), search));
      tags.sort(
          Comparator.comparing(Records.Tag::name, String.CASE_INSENSITIVE_ORDER)
              .thenComparingInt(Records.Tag::id));
      Map<String, Object> body = new LinkedHashMap<>();
      body.put("tags", tags.stream().map(tag -> Shapes.tagWithCount(tag, count(tag))).toList());
      return Answers.enveloped(200, body, true, "Success");
    }

    int userId = result.user().id();
    List<Records.Tag> tags = new ArrayList<>(visibleTags(userId));
    if (search != null) tags.removeIf(tag -> !containsIgnoringCase(tag.name(), search));

    int sort = queryNumber("sort") == null ? 0 : queryNumber("sort");
    sort(tags, sort);

    Integer cursor = queryNumber("cursor");
    if (cursor != null) {
      int at = -1;
      for (int i = 0; i < tags.size(); i++) if (tags.get(i).id() == cursor) at = i;
      if (at >= 0) tags = new ArrayList<>(tags.subList(at + 1, tags.size()));
    }
    int take = config.paginationTakeCount();
    boolean full = tags.size() >= take;
    if (full) tags = new ArrayList<>(tags.subList(0, take));

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("tags", tags.stream().map(tag -> Shapes.tagWithCount(tag, count(tag))).toList());
    // R66 — a full page names its last identifier as the next cursor; a short one names nothing.
    body.put("nextCursor", full && !tags.isEmpty() ? tags.get(tags.size() - 1).id() : null);
    return Answers.enveloped(200, body, true, "Success");
  }

  /** The caller's own tags, and the tags on links in collections the caller is a member of. */
  private List<Records.Tag> visibleTags(int userId) {
    Map<Integer, Records.Tag> byId = new LinkedHashMap<>();
    for (Records.Tag tag : data.tagsOwnedBy(userId)) byId.put(tag.id(), tag);
    for (Records.Collection collection : data.reachableCollections(userId)) {
      if (collection.ownerId() == userId) continue;
      for (LinksView.LinkRow row : data.linkRowsIn(collection.id())) {
        for (int tagId : row.tagIds()) {
          if (byId.containsKey(tagId)) continue;
          data.tag(tagId).ifPresent(tag -> byId.put(tag.id(), tag));
        }
      }
    }
    return new ArrayList<>(byId.values());
  }

  private static boolean containsIgnoringCase(String value, String needle) {
    return value != null && value.toLowerCase().contains(needle.toLowerCase());
  }

  private long count(Records.Tag tag) {
    return data.linkIdsWithTag(tag.id()).size();
  }

  /** SPEC-001 §2.3 TagSort — the four link orders, then the two by how many links carry the tag. */
  private void sort(List<Records.Tag> tags, int sort) {
    switch (sort) {
      case 1 -> tags.sort(Comparator.comparingInt(Records.Tag::id));
      case 2 -> tags.sort(
          Comparator.comparing(Records.Tag::name, String.CASE_INSENSITIVE_ORDER)
              .thenComparingInt(Records.Tag::id));
      case 3 -> tags.sort(
          Comparator.comparing(Records.Tag::name, String.CASE_INSENSITIVE_ORDER)
              .reversed()
              .thenComparing(Comparator.comparingInt(Records.Tag::id).reversed()));
      case 4 -> tags.sort(
          Comparator.comparingLong(this::count)
              .reversed()
              .thenComparing(Comparator.comparingInt(Records.Tag::id).reversed()));
      case 5 -> tags.sort(
          Comparator.comparingLong(this::count).thenComparingInt(Records.Tag::id));
      default -> tags.sort(Comparator.comparingInt(Records.Tag::id).reversed());
    }
  }

  @Get("/tags/{id}")
  public HttpResponse read(int id) {
    Caller.Result result = signedIn();
    if (result.refused()) return result.refusal();
    if (id == 0) return Answers.wrapped(401, "Please choose a valid name for the tag.");

    Optional<Records.Tag> found =
        visibleTags(result.user().id()).stream().filter(tag -> tag.id() == id).findFirst();
    if (found.isEmpty()) return Answers.wrapped(404, "Tag not found.");
    return Answers.wrapped(200, Shapes.tagWithCount(found.get(), count(found.get())));
  }

  // ------------------------------------------------------------------
  // writing
  // ------------------------------------------------------------------

  /** SPEC-001 R69 — each named tag found or made on the caller's own name, all six fields written. */
  @Post("/tags")
  public HttpResponse upsert(JsonNode body) {
    Caller.Result result = signedIn();
    if (result.refused()) return result.refusal();
    if (config.demoMode()) return Answers.demoRefusal();

    Instant now = Instant.now();
    int userId = result.user().id();
    List<Map<String, Object>> written = new ArrayList<>();
    for (JsonNode node : Bodies.array(body, "tags")) {
      String label = Bodies.text(node, "label");
      Optional<Validation.Issue> issue = Validation.requiredString("tags, label", label, 1, 50);
      if (issue.isPresent()) return Answers.issue(issue.get());

      Records.Tag tag = data.findOrCreateTag(userId, label, now);
      Records.Tag updated =
          data.client()
              .forKeyValueEntity(Ids.tag(tag.id()))
              .method(TagEntity::setArchivalFields)
              .invoke(
                  new TagEntity.SetArchivalFields(
                      Bodies.flag(node, "archiveAsScreenshot"),
                      Bodies.flag(node, "archiveAsMonolith"),
                      Bodies.flag(node, "archiveAsPDF"),
                      Bodies.flag(node, "archiveAsReadable"),
                      Bodies.flag(node, "archiveAsWaybackMachine"),
                      Bodies.flag(node, "aiTag"),
                      now));
      written.add(Shapes.tagWithCount(updated, count(updated)));
    }
    return Answers.wrapped(200, written);
  }

  /** SPEC-001 R67 — a name the caller already uses is refused before ownership is checked. */
  @Put("/tags/{id}")
  public HttpResponse rename(int id, JsonNode body) {
    Caller.Result result = signedIn();
    if (result.refused()) return result.refusal();
    if (config.demoMode()) return Answers.demoRefusal();

    String name = Bodies.text(body, "name");
    Optional<Validation.Issue> issue = Validation.requiredString("name", name, 1, 50);
    if (issue.isPresent()) return Answers.issue(issue.get());

    int userId = result.user().id();
    if (data.tagNamed(userId, name).isPresent()) {
      return Answers.wrapped(400, "Tag names should be unique.");
    }
    Optional<Records.Tag> found = data.tag(id);
    if (found.isEmpty() || found.get().ownerId() != userId) {
      return Answers.wrapped(401, "Permission denied.");
    }

    Instant now = Instant.now();
    Records.Tag renamed =
        data.client()
            .forKeyValueEntity(Ids.tag(id))
            .method(TagEntity::rename)
            .invoke(new TagEntity.Rename(name, now));
    data.release(Ids.tagNamed(userId, found.get().name()));
    data.claim(Ids.tagNamed(userId, name), id);
    clearIndexOfLinksCarrying(List.of(id), now);
    return Answers.wrapped(200, Shapes.tagWithCount(renamed, count(renamed)));
  }

  /** SPEC-001 R68 — the links keep their place and one new tag takes the old ones' place on them. */
  @Put("/tags/merge")
  public HttpResponse merge(JsonNode body) {
    Caller.Result result = signedIn();
    if (result.refused()) return result.refusal();
    if (config.demoMode()) return Answers.demoRefusal();

    List<Integer> tagIds = Bodies.integers(body, "tagIds");
    String newTagName = Bodies.text(body, "newTagName");
    Optional<Validation.Issue> issue =
        Validation.first(
            tagIds == null || tagIds.isEmpty()
                ? Optional.of(Validation.tooSmallArray("tagIds", 1))
                : Optional.<Validation.Issue>empty(),
            Validation.requiredString("newTagName", newTagName, 1, 50));
    if (issue.isPresent()) return Answers.issue(issue.get());

    int userId = result.user().id();
    List<Integer> owned =
        tagIds.stream()
            .filter(id -> data.tag(id).map(tag -> tag.ownerId() == userId).orElse(false))
            .toList();
    List<Integer> affected = linksCarrying(owned);

    Instant now = Instant.now();
    for (int id : owned) {
      data.tag(id).ifPresent(data::unindexTag);
      data.client().forKeyValueEntity(Ids.tag(id)).method(TagEntity::delete).invoke(now);
    }
    Records.Tag replacement = data.findOrCreateTag(userId, newTagName, now);
    for (int linkId : affected) {
      Records.Link link = data.link(linkId).orElse(null);
      if (link == null) continue;
      List<Integer> kept = new ArrayList<>();
      for (int tagId : link.tagIds()) if (!owned.contains(tagId)) kept.add(tagId);
      if (!kept.contains(replacement.id())) kept.add(replacement.id());
      data.client().forKeyValueEntity(Ids.link(linkId)).method(LinkEntity::setTags).invoke(kept);
      data.retag(linkId, link.tagIds(), kept);
    }
    return Answers.wrapped(200, Shapes.tag(replacement));
  }

  @Delete("/tags/{id}")
  public HttpResponse delete(int id) {
    Caller.Result result = signedIn();
    if (result.refused()) return result.refusal();
    if (config.demoMode()) return Answers.demoRefusal();
    if (id == 0) return Answers.wrapped(401, "Please choose a valid name for the tag.");

    Optional<Records.Tag> found = data.tag(id);
    if (found.isEmpty() || found.get().ownerId() != result.user().id()) {
      return Answers.wrapped(401, "Permission denied.");
    }
    Instant now = Instant.now();
    removeFromLinks(List.of(id), now);
    data.unindexTag(found.get());
    data.client().forKeyValueEntity(Ids.tag(id)).method(TagEntity::delete).invoke(now);
    return Answers.wrapped(200, Shapes.tag(found.get()));
  }

  /** The bulk delete answers how many were removed, not which. */
  @Delete("/tags")
  public HttpResponse bulkDelete(JsonNode body) {
    Caller.Result result = signedIn();
    if (result.refused()) return result.refusal();
    if (config.demoMode()) return Answers.demoRefusal();

    List<Integer> tagIds = Bodies.integers(body, "tagIds");
    if (tagIds == null || tagIds.isEmpty()) {
      return Answers.issue(Validation.tooSmallArray("tagIds", 1));
    }
    int userId = result.user().id();
    List<Integer> owned =
        tagIds.stream()
            .filter(id -> data.tag(id).map(tag -> tag.ownerId() == userId).orElse(false))
            .toList();
    Instant now = Instant.now();
    removeFromLinks(owned, now);
    for (int id : owned) {
      data.tag(id).ifPresent(data::unindexTag);
      data.client().forKeyValueEntity(Ids.tag(id)).method(TagEntity::delete).invoke(now);
    }
    return Answers.wrapped(200, owned.size());
  }

  private List<Integer> linksCarrying(List<Integer> tagIds) {
    Set<Integer> ids = new LinkedHashSet<>();
    for (int tagId : tagIds) ids.addAll(data.linkIdsWithTag(tagId));
    return new ArrayList<>(ids);
  }

  /** SPEC-001 R67 — every link that carried a removed tag is offered to the index again. */
  private void removeFromLinks(List<Integer> tagIds, Instant now) {
    for (int linkId : linksCarrying(tagIds)) {
      Records.Link link = data.link(linkId).orElse(null);
      if (link == null) continue;
      List<Integer> kept = new ArrayList<>();
      for (int tagId : link.tagIds()) if (!tagIds.contains(tagId)) kept.add(tagId);
      data.client().forKeyValueEntity(Ids.link(linkId)).method(LinkEntity::setTags).invoke(kept);
      data.retag(linkId, link.tagIds(), kept);
    }
  }

  private void clearIndexOfLinksCarrying(List<Integer> tagIds, Instant now) {
    for (int linkId : linksCarrying(tagIds)) {
      data.client()
          .forKeyValueEntity(Ids.link(linkId))
          .method(LinkEntity::setIndexVersion)
          .invoke(new LinkEntity.SetIndexVersion(null, now));
    }
  }
}

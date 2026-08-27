package io.akka.linkwarden.api;

import akka.http.javadsl.model.HttpResponse;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Put;
import com.fasterxml.jackson.databind.JsonNode;
import io.akka.linkwarden.application.Data;
import io.akka.linkwarden.application.LinksView;
import io.akka.linkwarden.application.UserEntity;
import io.akka.linkwarden.domain.Config;
import io.akka.linkwarden.domain.Ids;
import io.akka.linkwarden.domain.Permissions;
import io.akka.linkwarden.domain.Records;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The two dashboards. SPEC-001 R71–R72.
 *
 * <p>They are two different answers rather than two versions of one: the first is a flat list of
 * links, the second is that list plus one per collection section, a count of pinned links and a
 * count of tags, under the other envelope.
 */
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
@HttpEndpoint("/api")
public class DashboardEndpoint extends Surface {

  private static final int V1_TAKE = 10;
  private static final int V2_TAKE = 16;

  public DashboardEndpoint(Data data, Config config) {
    super(data, config);
  }

  /** SPEC-001 R71 — ten pinned and ten recent, merged, de-duplicated, newest identifier first. */
  @Get("/v1/dashboard")
  public HttpResponse v1() {
    Caller.Result result = signedIn();
    if (result.refused()) return result.refusal();
    int userId = result.user().id();

    List<LinksView.LinkRow> reachable = data.reachableLinkRows(userId);
    List<LinksView.LinkRow> pinned = take(reachable.stream().filter(row -> row.pinnedBy().contains(userId)).toList(), V1_TAKE);
    List<LinksView.LinkRow> recent = take(reachable, V1_TAKE);
    return Answers.wrapped(200, merged(recent, pinned, userId, false));
  }

  @Get("/v2/dashboard")
  public HttpResponse v2() {
    Caller.Result result = signedIn();
    if (result.refused()) return result.refusal();
    return Answers.enveloped(200, v2Body(result.user()), true, "Dashboard data fetched successfully.");
  }

  /** SPEC-001 R71 — the whole second dashboard, which the layout route also answers with. */
  public Map<String, Object> v2Body(Records.User user) {
    int userId = user.id();
    List<LinksView.LinkRow> reachable = data.reachableLinkRows(userId);
    List<LinksView.LinkRow> pinnedRows =
        reachable.stream().filter(row -> row.pinnedBy().contains(userId)).toList();

    List<Records.DashboardSection> sections =
        user.dashboardSections() == null ? List.of() : user.dashboardSections();
    boolean viewPinned = sections.stream().anyMatch(s -> "PINNED_LINKS".equals(s.type()));
    boolean viewRecent = sections.stream().anyMatch(s -> "RECENT_LINKS".equals(s.type()));
    List<Records.DashboardSection> collectionSections =
        sections.stream().filter(s -> "COLLECTION".equals(s.type())).toList();

    Map<String, Object> body = new LinkedHashMap<>();
    long numberOfTags = visibleTagCount(userId);
    // R71 — with no link section of any kind enabled the answer stops here, and carries neither
    // a links list worth reading nor the per-collection map.
    if (!viewPinned && !viewRecent && collectionSections.isEmpty()) {
      body.put("links", List.of());
      body.put("numberOfPinnedLinks", pinnedRows.size());
      body.put("numberOfTags", numberOfTags);
      return body;
    }

    List<LinksView.LinkRow> pinned = viewPinned ? take(pinnedRows, V2_TAKE) : List.of();
    List<LinksView.LinkRow> recent = viewRecent ? take(reachable, V2_TAKE) : List.of();

    Map<String, Object> collectionLinks = new LinkedHashMap<>();
    for (Records.DashboardSection section : collectionSections) {
      if (section.collectionId() == null) continue;
      Optional<Records.Collection> collection = data.collection(section.collectionId());
      if (collection.isEmpty() || !Permissions.canRead(collection.get().asSubject(), userId)) continue;
      List<LinksView.LinkRow> inside =
          take(reachable.stream().filter(row -> row.collectionId() == section.collectionId()).toList(), V2_TAKE);
      collectionLinks.put(
          String.valueOf(section.collectionId()), inside.stream().map(row -> shape(row, userId, true)).toList());
    }

    body.put("links", merged(recent, pinned, userId, true));
    body.put("collectionLinks", collectionLinks);
    body.put("numberOfPinnedLinks", pinnedRows.size());
    body.put("numberOfTags", numberOfTags);
    return body;
  }

  /** SPEC-001 R72 — a section naming a collection the caller cannot reach is dropped, not refused. */
  @Put("/v2/dashboard")
  public HttpResponse updateLayout(JsonNode body) {
    Caller.Result result = signedIn();
    if (result.refused()) return result.refusal();
    if (config.demoMode()) return Answers.demoRefusal();

    int userId = result.user().id();
    List<Records.DashboardSection> sections = new ArrayList<>();
    int nextId = 1;
    List<JsonNode> given = new ArrayList<>();
    if (body != null && body.isArray()) body.forEach(given::add);
    for (JsonNode node : given) {
      Integer collectionId = Bodies.number(node, "collectionId");
      if (collectionId != null) {
        Optional<Records.Collection> collection = data.collection(collectionId);
        if (collection.isEmpty() || !Permissions.canRead(collection.get().asSubject(), userId)) {
          continue;
        }
      }
      if (!Bodies.isOn(node, "enabled")) continue;
      Integer order = Bodies.number(node, "order");
      sections.add(
          new Records.DashboardSection(
              nextId++, userId, collectionId, Bodies.text(node, "type"), order == null ? 0 : order));
    }

    Records.User updated =
        data.client()
            .forKeyValueEntity(Ids.user(userId))
            .method(UserEntity::setDashboardSections)
            .invoke(new UserEntity.SetDashboardSections(sections, Instant.now()));
    // The layout route answers with the whole of what the second dashboard would answer,
    // its own status among it, rather than with the envelope a read wears.
    Map<String, Object> answer = new LinkedHashMap<>();
    answer.put("data", v2Body(updated));
    answer.put("message", "Dashboard data fetched successfully.");
    answer.put("statusCode", 200);
    answer.put("success", true);
    return Answers.json(200, answer);
  }

  // ------------------------------------------------------------------

  private static List<LinksView.LinkRow> take(List<LinksView.LinkRow> rows, int count) {
    return rows.size() <= count ? rows : rows.subList(0, count);
  }

  /** Recent then pinned, first occurrence kept, then newest identifier first. */
  private List<Map<String, Object>> merged(
      List<LinksView.LinkRow> recent, List<LinksView.LinkRow> pinned, int userId,
      boolean omitText) {
    Map<Integer, LinksView.LinkRow> byId = new LinkedHashMap<>();
    for (LinksView.LinkRow row : recent) byId.putIfAbsent(row.id(), row);
    for (LinksView.LinkRow row : pinned) byId.putIfAbsent(row.id(), row);
    List<LinksView.LinkRow> all = new ArrayList<>(byId.values());
    all.sort(java.util.Comparator.comparingInt(LinksView.LinkRow::id).reversed());
    return all.stream().map(row -> shape(row, userId, omitText)).toList();
  }

  /** A dashboard link carries only the caller's own pin, never anybody else's. */
  private Map<String, Object> shape(LinksView.LinkRow row, int userId, boolean omitText) {
    Records.Link link = data.link(row.id()).orElse(null);
    if (link == null) return new LinkedHashMap<>();
    List<Integer> ownPin = link.pinnedBy().contains(userId) ? List.of(userId) : List.of();
    // The second dashboard leaves the page's own text out; the first does not, and both
    // draw their rows through here, so which one is asking decides.
    return Shapes.link(
        link,
        data.collection(link.collectionId()).orElse(null),
        data.tagsOf(link),
        ownPin,
        omitText);
  }

  private long visibleTagCount(int userId) {
    Set<Integer> ids = new LinkedHashSet<>();
    for (Records.Tag tag : data.tagsOwnedBy(userId)) ids.add(tag.id());
    for (Records.Collection collection : data.reachableCollections(userId)) {
      if (collection.ownerId() == userId) continue;
      for (LinksView.LinkRow row : data.linkRowsIn(collection.id())) ids.addAll(row.tagIds());
    }
    return ids.size();
  }
}

package io.akka.linkwarden.api;

import akka.http.javadsl.model.HttpResponse;
import akka.http.javadsl.model.headers.RawHeader;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Post;
import com.fasterxml.jackson.databind.JsonNode;
import io.akka.linkwarden.application.CollectionEntity;
import io.akka.linkwarden.application.Data;
import io.akka.linkwarden.application.LinkEntity;
import io.akka.linkwarden.application.LinkWriter;
import io.akka.linkwarden.application.LinksView;
import io.akka.linkwarden.domain.Config;
import io.akka.linkwarden.domain.Ids;
import io.akka.linkwarden.domain.Importers;
import io.akka.linkwarden.domain.Records;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Taking an account out and bringing one in. SPEC-001 R78–R83. */
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
@HttpEndpoint("/api/v1")
public class MigrationEndpoint extends Surface {

  private final LinkWriter writer;

  public MigrationEndpoint(Data data, Config config, LinkWriter writer) {
    super(data, config);
    this.writer = writer;
  }

  /** SPEC-001 R82 — the whole account, minus the password, the identifier and the archives. */
  @Get("/migration")
  public HttpResponse export() {
    Caller.Result result = signedIn();
    if (result.refused()) return result.refusal();

    Records.User user = result.user();
    Map<String, Object> out = Shapes.user(user);
    out.remove("id");

    List<Map<String, Object>> collections = new ArrayList<>();
    for (Records.Collection collection : data.reachableCollections(user.id())) {
      if (collection.ownerId() != user.id()) continue;
      Map<String, Object> shaped = Shapes.shortCollection(collection);
      shaped.put(
          "rssSubscriptions",
          data.feedsOwnedBy(user.id()).stream()
              .filter(feed -> feed.collectionId() == collection.id())
              .map(feed -> Shapes.feed(feed, null))
              .toList());
      // Ascending by key, unlike every listing route: the export reads the collection's links
      // through the relation without an order of its own, which is the order they were written.
      List<Integer> ids =
          data.linkRowsIn(collection.id()).stream()
              .map(LinksView.LinkRow::id)
              .sorted()
              .toList();
      List<Map<String, Object>> links = new ArrayList<>();
      for (int linkId : ids) {
        data.link(linkId).ifPresent(link -> links.add(exported(link)));
      }
      shaped.put("links", links);
      collections.add(shaped);
    }
    out.put("collections", collections);
    // The pinned links are the plain records: no tags beside them, and the page's own text
    // still on them, because they are read without the two the collections are read with.
    out.put(
        "pinnedLinks",
        data.pinnedLinkRows(user.id()).stream()
            .map(row -> data.link(row.id()))
            .flatMap(Optional::stream)
            .map(link -> Shapes.link(link, null, List.of(), null, false))
            .map(shaped -> {
              shaped.remove("tags");
              return shaped;
            })
            .toList());

    byte[] bytes;
    try {
      bytes = Answers.mapper().writeValueAsBytes(out);
    } catch (Exception e) {
      throw new IllegalStateException("could not write the export", e);
    }
    return Answers.text(
        200,
        "application/json",
        bytes,
        List.of(RawHeader.create("Content-Disposition", "attachment; filename=backup.json")));
  }

  /** The five preservation fields and the page's text are dropped, to keep the file small. */
  private Map<String, Object> exported(Records.Link link) {
    Map<String, Object> shaped =
        new LinkedHashMap<>(
            Shapes.link(link, null, data.tagsOf(link), null, true));
    shaped.remove("preview");
    shaped.remove("image");
    shaped.remove("pdf");
    shaped.remove("readable");
    shaped.remove("monolith");
    shaped.remove("textContent");
    return shaped;
  }

  /** SPEC-001 R78–R83 — one of five formats, capacity for the whole file first. */
  @Post("/migration")
  public HttpResponse importFile(JsonNode body) {
    Caller.Result result = signedIn();
    if (result.refused()) return result.refusal();
    if (config.demoMode()) return Answers.demoRefusal();

    Integer format = Bodies.number(body, "format");
    String raw = Bodies.text(body, "data");
    if (format == null || raw == null) {
      return Answers.wrapped(400, "Invalid request body provided.");
    }
    int limitMb = config.importLimitMb();
    if (raw.getBytes(StandardCharsets.UTF_8).length > (long) limitMb * 1024 * 1024) {
      return Answers.wrapped(413, "Import file exceeds the " + limitMb + "MB size limit.");
    }

    Importers.Plan plan;
    try {
      plan =
          switch (format) {
            case 0 -> Importers.fromLinkwarden(raw);
            case 1 -> Importers.fromHtml(raw);
            case 2 -> Importers.fromWallabag(raw);
            case 3 -> Importers.fromOmnivore(raw);
            case 4 -> Importers.fromPocket(raw);
            default -> null;
          };
    } catch (Exception e) {
      return Answers.wrapped(400, "Invalid request body provided.");
    }
    if (plan == null) return Answers.wrapped(400, "Invalid request body provided.");

    Records.User user = result.user();
    Instant now = Instant.now();
    if (writer.hasPassedLimit(user, plan.linkCount(), now)) {
      return Answers.wrapped(
          400, "Your subscription has reached the maximum number of links allowed.");
    }

    // A plan's collections are created first and in order, because a nested one names its parent
    // by the position of the collection it sits under.
    // R79 -- a bookmark file naming one folder twice gets one collection; R80 -- the four
    // foreign importers make their own collection every time they are run, whatever it is
    // called and whatever ran before.
    boolean reuse = format == 1;
    List<Records.Collection> created = new ArrayList<>();
    for (Importers.PlannedCollection planned : plan.collections()) {
      Integer parentId =
          planned.parentIndex() >= 0 && planned.parentIndex() < created.size()
              ? created.get(planned.parentIndex()).id()
              : null;
      created.add(reuse
          ? collectionNamed(user, planned.name(), parentId, now)
          : freshCollection(user, planned.name(), parentId, now));
    }

    for (Importers.PlannedLink planned : plan.links()) {
      Records.Collection into =
          planned.collectionIndex() >= 0 && planned.collectionIndex() < created.size()
              ? created.get(planned.collectionIndex())
              : writer.unorganized(user, now);
      Records.Link link =
          writer.create(
              user,
              into,
              new LinkWriter.Proposal(
                  planned.name(),
                  planned.url(),
                  "url",
                  planned.description(),
                  planned.textContent(),
                  planned.image(),
                  planned.tags(),
                  planned.importDate()),
              false,
              now);
      if (planned.pinned()) {
        data.client()
            .forKeyValueEntity(Ids.link(link.id()))
            .method(LinkEntity::pin)
            .invoke(new LinkEntity.Pin(user.id(), true, now));
      }
    }
    return Answers.wrapped(200, "Success.");
  }

  /**
   * A collection an import names, reused when the file names it twice.
   *
   * <p>Reuse is by name and parent within the caller's own collections, which is what keeps a
   * bookmark file listing the same folder in two places from making two of them.
   */
  private Records.Collection collectionNamed(
      Records.User user, String name, Integer parentId, Instant now) {
    for (Records.Collection existing : data.reachableCollections(user.id())) {
      if (existing.ownerId() == user.id()
          && existing.name().equals(name)
          && java.util.Objects.equals(existing.parentId(), parentId)) {
        return existing;
      }
    }
    return freshCollection(user, name, parentId, now);
  }

  /** A collection this import makes, whatever else is called the same. */
  private Records.Collection freshCollection(
      Records.User user, String name, Integer parentId, Instant now) {
    int id = data.nextId("collection");
    data.client()
        .forKeyValueEntity(Ids.collection(id))
        .method(CollectionEntity::create)
        .invoke(
            new CollectionEntity.Create(
                id, name, null, null, null, null, parentId, user.id(), user.id(), List.of(), now));
    // Not appended to the account's collection order: an import writes the collection row and
    // nothing else, so a file of five folders leaves the order as it found it.
    Records.Collection created = data.collection(id).orElseThrow();
    data.indexCollection(created, null);
    return created;
  }
}

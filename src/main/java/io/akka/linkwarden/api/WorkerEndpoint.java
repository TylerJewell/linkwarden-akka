package io.akka.linkwarden.api;

import akka.http.javadsl.model.HttpResponse;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Delete;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import com.fasterxml.jackson.databind.JsonNode;
import io.akka.linkwarden.application.Data;
import io.akka.linkwarden.application.FileStore;
import io.akka.linkwarden.application.LinkEntity;
import io.akka.linkwarden.application.LinksView;
import io.akka.linkwarden.domain.ArchivalSettings;
import io.akka.linkwarden.domain.ArchivalSettingsResolver;
import io.akka.linkwarden.domain.Config;
import io.akka.linkwarden.domain.Eligibility;
import io.akka.linkwarden.domain.Ids;
import io.akka.linkwarden.domain.Records;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** What the administrator sees of the preservation work, and what they may reset. SPEC-001 R56, R97. */
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
@HttpEndpoint("/api/v1/worker")
public class WorkerEndpoint extends Surface {

  private static final String UNAVAILABLE = Records.Link.UNAVAILABLE;

  private final FileStore files;

  public WorkerEndpoint(Data data, Config config, FileStore files) {
    super(data, config);
    this.files = files;
  }

  /**
   * SPEC-001 R97 — counted across the whole instance rather than per account.
   *
   * <p>The three link counts overlap on purpose: a link whose formats are partly there is both
   * done and not failed, and one still waiting is neither. They are three separate questions
   * asked of the same set, not a partition of it.
   */
  @Get("")
  public HttpResponse stats() {
    Caller.Result result = signedIn();
    if (result.refused()) return result.refusal();
    if (!caller.isAdministrator(result.user())) return Answers.wrapped(403, "Forbidden.");

    long pending = 0;
    long done = 0;
    long failed = 0;
    long searchPending = 0;
    long searchDone = 0;
    for (Records.Link row : data.allLinks()) {
      boolean hasUrl = row.url() != null;
      boolean preserved = row.lastPreserved() != null;
      if (hasUrl && !preserved) pending++;
      boolean anyFormat =
          !UNAVAILABLE.equals(row.image())
              || !UNAVAILABLE.equals(row.pdf())
              || !UNAVAILABLE.equals(row.readable())
              || !UNAVAILABLE.equals(row.monolith());
      if (preserved && anyFormat) done++;
      if (hasUrl
          && preserved
          && UNAVAILABLE.equals(row.image())
          && UNAVAILABLE.equals(row.pdf())
          && UNAVAILABLE.equals(row.readable())
          && UNAVAILABLE.equals(row.monolith())) {
        failed++;
      }
      if (row.indexVersion() != null && row.indexVersion() == Eligibility.CURRENT_INDEX_VERSION) {
        searchDone++;
      } else {
        searchPending++;
      }
    }

    Map<String, Object> link = new LinkedHashMap<>();
    link.put("pending", pending);
    link.put("done", done);
    link.put("failed", failed);
    Map<String, Object> search = new LinkedHashMap<>();
    search.put("pending", searchPending);
    search.put("done", searchDone);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("link", link);
    body.put("search", search);
    return Answers.enveloped(200, body, true, "Worker stats fetched successfully.");
  }

  /** SPEC-001 R56 — two repairs, and only over the administrator's own collections. */
  @Delete("/preservation")
  public HttpResponse resetPreservation(JsonNode body) {
    Caller.Result result = signedIn();
    if (result.refused()) return result.refusal();
    if (!caller.isAdministrator(result.user())) return Answers.wrapped(403, "Forbidden.");
    if (config.demoMode()) return Answers.demoRefusal();

    String action = Bodies.text(body, "action");
    if (!"allAndRePreserve".equals(action) && !"allBroken".equals(action)) {
      return Answers.issue(
          io.akka.linkwarden.domain.Validation.invalidStringOption(
              "action", List.of("allAndRePreserve", "allBroken")));
    }

    Records.User admin = result.user();
    Instant now = Instant.now();
    List<Records.Link> owned = new ArrayList<>();
    for (Records.Collection collection : data.reachableCollections(admin.id())) {
      if (collection.ownerId() != admin.id()) continue;
      for (LinksView.LinkRow row : data.linkRowsIn(collection.id())) {
        data.link(row.id())
            .filter(link -> "url".equals(link.type()) && link.url() != null)
            .ifPresent(owned::add);
      }
    }

    for (Records.Link link : owned) {
      if ("allAndRePreserve".equals(action)) {
        files.removeLinkFiles(link.collectionId(), link.id());
        data.client()
            .forKeyValueEntity(Ids.link(link.id()))
            .method(LinkEntity::rePreserve)
            .invoke(now);
      } else {
        // R56 — only the formats that failed and are still wanted; a link none of whose broken
        // formats is wanted is left as it is, so running this twice does not re-queue the world.
        ArchivalSettings wanted =
            ArchivalSettingsResolver.resolve(
                data.tagsOf(link).stream().map(Records.Tag::asArchivalTag).toList(),
                admin.archivalSettings());
        data.client()
            .forKeyValueEntity(Ids.link(link.id()))
            .method(LinkEntity::repairBroken)
            .invoke(new LinkEntity.RepairBroken(wanted, now));
      }
    }
    return Answers.wrapped(200, "Success.");
  }
}

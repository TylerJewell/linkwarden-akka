package io.akka.linkwarden.api;

import akka.NotUsed;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.http.AbstractHttpEndpoint;
import akka.javasdk.http.HttpResponses;
import akka.http.javadsl.model.HttpResponse;
import akka.stream.javadsl.Source;
import io.akka.linkwarden.application.LinksView;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * What linkwarden's own dashboard reads, served as a stream.
 *
 * <p>RENDERING.md R1: the front end this port ships is linkwarden's own, and the one thing
 * changed in it is where this screen's data comes from — a subscription here instead of the
 * repeated fetch its `useDashboardData` hook used to make. R4's test is that deleting the
 * subscription leaves the list with no other route to its data, which holds: the hook no
 * longer knows the address of a request that returns links.
 *
 * <p>The payload is linkwarden's own shape, because the interface reading it was not
 * changed. Everything on it that this port's slice does not own — the collection a link
 * sits in, the counts beside the heading — is carried as it was given, so that a difference
 * on the screen is a difference in the archiving pipeline and not in something neither
 * system was being asked about.
 */
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
@HttpEndpoint("/dashboard")
public class DashboardEndpoint extends AbstractHttpEndpoint {

  /** One link, in the shape linkwarden's link card reads. */
  public record DashboardLink(
      long id,
      String name,
      String type,
      String description,
      long createdById,
      long collectionId,
      String icon,
      String iconWeight,
      String color,
      String url,
      String preview,
      String image,
      String pdf,
      String readable,
      String monolith,
      boolean clientSide,
      boolean aiTagged,
      String metaDescription,
      Integer indexVersion,
      String lastPreserved,
      String importDate,
      String createdAt,
      String updatedAt,
      List<Object> tags,
      Collection collection,
      List<Object> pinnedBy) {}

  public record Collection(
      long id,
      String name,
      String description,
      String icon,
      String iconWeight,
      String color,
      Long parentId,
      boolean isPublic,
      long ownerId,
      long createdById,
      String createdAt,
      String updatedAt) {}

  public record Dashboard(
      List<DashboardLink> links,
      List<Object> pinnedLinks,
      long numberOfLinks,
      long numberOfCollections,
      long numberOfTags,
      long numberOfPinnedLinks,
      List<Object> dashboardSections) {}

  public record Envelope(Dashboard data) {}

  private static final Duration TICK = Duration.ofSeconds(1);

  private final ComponentClient client;

  public DashboardEndpoint(ComponentClient client) {
    this.client = client;
  }

  @Get("/stream")
  public HttpResponse stream() {
    // A first element straight away so the screen is never empty while it waits, then one
    // whenever the read side has moved. The client holds the connection open, so nothing
    // here is a request the page makes again.
    Source<Envelope, NotUsed> updates =
        Source.tick(Duration.ZERO, TICK, "tick")
            .map(ignored -> current())
            .statefulMapConcat(
                () -> {
                  final Envelope[] last = new Envelope[1];
                  return envelope -> {
                    if (last[0] != null && last[0].equals(envelope)) {
                      return List.of();
                    }
                    last[0] = envelope;
                    return List.of(envelope);
                  };
                })
            .mapMaterializedValue(m -> NotUsed.getInstance());
    return HttpResponses.serverSentEvents(updates);
  }

  @Get
  public Envelope snapshot() {
    return current();
  }

  private Envelope current() {
    var rows = client.forView().method(LinksView::all).invoke().links();
    List<DashboardLink> links = new ArrayList<>();
    for (LinksView.LinkEntry r : rows) {
      links.add(
          new DashboardLink(
              Long.parseLong(r.linkId()),
              r.title(),
              r.type().name().toLowerCase(),
              "",
              1,
              Long.parseLong(r.collectionId()),
              null,
              null,
              null,
              r.url().orElse(null),
              r.preview().orElse(null),
              r.image().orElse(null),
              r.pdf().orElse(null),
              r.readable().orElse(null),
              r.monolith().orElse(null),
              false,
              false,
              r.metaDescription().orElse(null),
              r.indexVersion() == 0 ? null : r.indexVersion(),
              r.lastPreserved().map(Instant::toString).orElse(null),
              null,
              r.createdAt().toString(),
              r.lastPreserved().orElse(r.createdAt()).toString(),
              List.of(),
              unorganised(),
              List.of()));
    }
    links.sort((a, b) -> Long.compare(b.id(), a.id()));
    return new Envelope(
        new Dashboard(links, List.of(), links.size(), 1, 0, 0, List.of()));
  }

  private Collection unorganised() {
    return new Collection(
        1, "Unorganized", "", null, null, "#0ea5e9", null, false, 1, 1,
        "2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z");
  }
}

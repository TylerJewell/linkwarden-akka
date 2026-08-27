package io.akka.linkwarden.api;

import akka.http.javadsl.model.HttpResponse;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.http.HttpResponses;
import akka.stream.javadsl.Source;
import io.akka.linkwarden.application.ChangeFeed;
import io.akka.linkwarden.application.Data;
import io.akka.linkwarden.domain.Config;
import io.akka.linkwarden.domain.Records;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;

/**
 * The subscription the interface reads its dashboard from. RENDERING.md R1, SPEC-001 R98–R99.
 *
 * <p>Served beside the dashboard it carries rather than under a path of its own, because a
 * subscription cannot send a header: the interface opens it from its own origin and the token
 * this session holds is attached on the way through, exactly as for every other request.
 *
 * <p>The first message is the dashboard as it stands, so a client that has just reconnected is in
 * the same position as one connecting for the first time — it is told the current state rather
 * than left to work out what it missed while it was away. After that a message is sent only when
 * something the caller can reach has changed.
 */
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
@HttpEndpoint("/api/v2/dashboard")
public class StreamEndpoint extends Surface {

  /**
   * How long a silent connection waits before a heartbeat proves it is still there.
   *
   * <p>Short rather than long: the wait is a blocking read, and a cancelled subscription is only
   * noticed when it returns, so this is also how long a shutdown waits for the last reader.
   */
  private static final long HEARTBEAT_SECONDS = 5;

  private final ChangeFeed feed;
  private final DashboardEndpoint dashboard;

  public StreamEndpoint(Data data, Config config, ChangeFeed feed) {
    super(data, config);
    this.feed = feed;
    this.dashboard = new DashboardEndpoint(data, config);
  }

  /** One message per change the caller can see, and one on connecting. */
  @Get("/stream")
  public HttpResponse stream() {
    Optional<Records.User> viewer = caller.optional(authorization(), java.time.Instant.now());
    // A subscription with nobody behind it carries the empty dashboard rather than refusing:
    // the interface opens the connection before it knows whether the session is good, and a
    // refusal there would make the page retry in a loop.
    int userId = viewer.map(Records.User::id).orElse(0);

    Source<Map<String, Object>, ?> messages =
        Source.single(payload(userId))
            .concat(
                Source.unfoldResource(
                        feed::subscribe,
                        queue -> Optional.of(feed.nextOrHeartbeat(queue, HEARTBEAT_SECONDS)),
                        (BlockingQueue<ChangeFeed.Change> queue) -> feed.unsubscribe(queue))
                    .filter(change -> !change.isHeartbeat() && visible(change, userId))
                    .map(change -> payload(userId))
                    // A subscription is not held for ever: it ends after an hour and the
                    // interface's own reconnect opens the next one, which is the path R1
                    // requires anyway and is what keeps a forgotten tab from holding a reader.
                    .takeWithin(java.time.Duration.ofHours(1)));
    return HttpResponses.serverSentEvents(messages);
  }

  /** SPEC-001 R99 — a change nobody may see is not announced to them. */
  private boolean visible(ChangeFeed.Change change, int userId) {
    if (userId == 0) return false;
    return switch (change.kind()) {
      case "link" ->
          data.subjectForLink(change.id())
              .map(subject -> io.akka.linkwarden.domain.Permissions.canRead(subject, userId))
              .orElse(false);
      case "collection" ->
          data.collection(change.id())
              .map(collection ->
                  io.akka.linkwarden.domain.Permissions.canRead(collection.asSubject(), userId))
              .orElse(false);
      case "tag" -> data.tag(change.id()).map(tag -> tag.ownerId() == userId).orElse(false);
      case "highlight" ->
          data.highlight(change.id()).map(h -> h.userId() == userId).orElse(false);
      default -> false;
    };
  }

  /** The same body {@code GET /api/v2/dashboard} answers, under the envelope the screen reads. */
  private Map<String, Object> payload(int userId) {
    Map<String, Object> data0 =
        userId == 0
            ? emptyDashboard()
            : data.user(userId).map(dashboard::v2Body).orElseGet(StreamEndpoint::emptyDashboard);
    Map<String, Object> message = new LinkedHashMap<>();
    message.put("data", data0);
    return message;
  }

  private static Map<String, Object> emptyDashboard() {
    Map<String, Object> empty = new LinkedHashMap<>();
    empty.put("links", List.of());
    empty.put("collectionLinks", new LinkedHashMap<String, Object>());
    empty.put("numberOfPinnedLinks", 0);
    empty.put("numberOfTags", 0);
    return empty;
  }
}

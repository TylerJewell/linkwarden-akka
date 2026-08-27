package io.akka.linkwarden.api;

import akka.http.javadsl.model.HttpResponse;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Delete;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Post;
import com.fasterxml.jackson.databind.JsonNode;
import io.akka.linkwarden.application.Data;
import io.akka.linkwarden.application.HighlightEntity;
import io.akka.linkwarden.domain.Config;
import io.akka.linkwarden.domain.Ids;
import io.akka.linkwarden.domain.Permissions;
import io.akka.linkwarden.domain.Records;
import java.time.Instant;
import java.util.Optional;

/** Passages of a link somebody marked. SPEC-001 R70. */
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
@HttpEndpoint("/api/v1")
public class HighlightEndpoint extends Surface {

  public HighlightEndpoint(Data data, Config config) {
    super(data, config);
  }

  /**
   * SPEC-001 R70 — the same passage marked twice is recoloured rather than marked again.
   *
   * <p>What identifies "the same passage" is the pair of offsets, not the text: a caller sending
   * different text over the same offsets changes nothing but the colour and the comment.
   */
  @Post("/highlights")
  public HttpResponse create(JsonNode body) {
    Caller.Result result = signedIn();
    if (result.refused()) return result.refusal();
    if (config.demoMode()) return Answers.demoRefusal();

    Integer linkId = Bodies.number(body, "linkId");
    if (linkId == null) return Answers.issue(io.akka.linkwarden.domain.Validation.missing("linkId", "number"));

    int userId = result.user().id();
    Optional<Permissions.Subject> subject = data.subjectForLink(linkId);
    if (subject.isEmpty()
        || !(Permissions.isOwner(subject.get(), userId)
            || Permissions.canUpdate(subject.get(), userId))) {
      return Answers.wrapped(400, "Collection not accessible");
    }

    int startOffset = Bodies.number(body, "startOffset") == null ? 0 : Bodies.number(body, "startOffset");
    int endOffset = Bodies.number(body, "endOffset") == null ? 0 : Bodies.number(body, "endOffset");
    String color = Bodies.text(body, "color");
    String comment = Bodies.text(body, "comment");
    Instant now = Instant.now();

    Optional<Records.Highlight> existing =
        data.highlightsOn(linkId, userId).stream()
            .filter(h -> h.startOffset() == startOffset && h.endOffset() == endOffset)
            .findFirst();
    if (existing.isPresent()) {
      Records.Highlight recoloured =
          data.client()
              .forKeyValueEntity(Ids.highlight(existing.get().id()))
              .method(HighlightEntity::recolour)
              .invoke(new HighlightEntity.Recolour(color, comment, now));
      return Answers.wrapped(200, Shapes.highlight(recoloured));
    }

    int id = data.nextId("highlight");
    Records.Highlight created =
        data.client()
            .forKeyValueEntity(Ids.highlight(id))
            .method(HighlightEntity::create)
            .invoke(
                new HighlightEntity.Create(
                    id, linkId, userId, color, comment, startOffset, endOffset,
                    Bodies.text(body, "text"), now));
    data.addTo(Ids.highlightsOn(linkId, userId), id);
    return Answers.wrapped(200, Shapes.highlight(created));
  }

  /** The answer is the identifier of what went, which is what the interface removes from view. */
  @Delete("/highlights/{id}")
  public HttpResponse delete(int id) {
    Caller.Result result = signedIn();
    if (result.refused()) return result.refusal();
    if (config.demoMode()) return Answers.demoRefusal();
    if (id == 0) return Answers.wrapped(401, "Please choose a valid highlight.");

    Optional<Records.Highlight> found = data.highlight(id);
    if (found.isEmpty() || found.get().userId() != result.user().id()) {
      return Answers.wrapped(401, "Please choose a valid highlight.");
    }
    data.client()
        .forKeyValueEntity(Ids.highlight(id))
        .method(HighlightEntity::delete)
        .invoke(Instant.now());
    data.removeFrom(Ids.highlightsOn(found.get().linkId(), found.get().userId()), id);
    return Answers.wrapped(200, id);
  }
}

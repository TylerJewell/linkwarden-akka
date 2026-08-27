package io.akka.linkwarden.api;

import akka.http.javadsl.model.HttpResponse;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import io.akka.linkwarden.application.Data;
import io.akka.linkwarden.application.LinksView;
import io.akka.linkwarden.domain.Config;
import io.akka.linkwarden.domain.Permissions;
import io.akka.linkwarden.domain.Records;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * What a public collection shows to somebody who is not signed in. SPEC-001 R5, R64.
 *
 * <p>Nothing here asks who is calling. A collection is either public or it is not, and the four
 * routes read only that flag — which is why a signed-in caller sees exactly what a stranger does.
 */
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
@HttpEndpoint("/api/v1/public")
public class PublicEndpoint extends Surface {

  public PublicEndpoint(Data data, Config config) {
    super(data, config);
  }

  @Get("/collections/{id}")
  public HttpResponse collection(int id) {
    if (id == 0) return Answers.wrapped(401, "Please choose a valid collection.");
    Optional<Records.Collection> found = data.collection(id).filter(Records.Collection::isPublic);
    if (found.isEmpty()) return Answers.wrapped(400, "Collection not found.");

    Records.Collection collection = found.get();
    List<Map<String, Object>> members = new ArrayList<>();
    for (Permissions.Member member : collection.members()) {
      members.add(Shapes.member(member, data.user(member.userId()).orElse(null), collection.id()));
    }
    return Answers.wrapped(
        200, Shapes.collection(collection, data.countLinksIn(collection.id()), members));
  }

  /** A link in a collection that is not public answers 200 carrying nothing, not a refusal. */
  @Get("/links/{id}")
  public HttpResponse link(int id) {
    if (id == 0) return Answers.wrapped(401, "Please choose a valid link.");
    Optional<Records.Link> found =
        data.link(id)
            .filter(
                link ->
                    data.collection(link.collectionId())
                        .map(Records.Collection::isPublic)
                        .orElse(false));
    return Answers.wrapped(
        200,
        found
            .map(
                link ->
                    Shapes.link(
                        link,
                        data.collection(link.collectionId()).orElse(null),
                        data.tagsOf(link),
                        null,
                        false))
            .orElse(null));
  }

  @Get("/collections/links")
  public HttpResponse links() {
    Integer collectionId = queryNumber("collectionId");
    if (collectionId == null) return Answers.wrapped(400, "Please choose a valid collection.");

    return Answers.enveloped(
        200,
        LinkSearch.run(
            data,
            config,
            null,
            new LinkSearch.Request(
                queryNumber("cursor"),
                collectionId,
                null,
                queryFlag("pinnedOnly"),
                query("searchQueryString").orElse(null),
                queryNumber("sort") == null ? 0 : queryNumber("sort"),
                true)),
        true,
        "Success");
  }

  /** SPEC-001 R66's second half — a collection's tags, by name then identifier, and no cursor. */
  @Get("/collections/tags")
  public HttpResponse tags() {
    Integer collectionId = queryNumber("collectionId");
    if (collectionId == null) return Answers.wrapped(400, "Please choose a valid collection.");
    Optional<Records.Collection> collection =
        data.collection(collectionId).filter(Records.Collection::isPublic);
    if (collection.isEmpty()) return Answers.wrapped(404, "Collection not found.");

    String search = query("search").map(String::trim).filter(s -> !s.isEmpty()).orElse(null);
    List<Records.Tag> tags = new ArrayList<>();
    Set<Integer> seen = new LinkedHashSet<>();
    for (LinksView.LinkRow row : data.linkRowsIn(collectionId)) {
      for (int tagId : row.tagIds()) {
        if (!seen.add(tagId)) continue;
        data.tag(tagId).ifPresent(tags::add);
      }
    }
    if (search != null) {
      String needle = search.toLowerCase();
      tags.removeIf(tag -> tag.name() == null || !tag.name().toLowerCase().contains(needle));
    }
    tags.sort(
        Comparator.comparing(Records.Tag::name, String.CASE_INSENSITIVE_ORDER)
            .thenComparingInt(Records.Tag::id));

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("tags", tags.stream().map(tag -> Shapes.tagWithCount(tag, countOf(tag))).toList());
    return Answers.enveloped(200, body, true, "Success");
  }

  private long countOf(Records.Tag tag) {
    return data.linkIdsWithTag(tag.id()).size();
  }
}

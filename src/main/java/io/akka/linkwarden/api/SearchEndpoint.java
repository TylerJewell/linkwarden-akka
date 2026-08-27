package io.akka.linkwarden.api;

import akka.http.javadsl.model.HttpResponse;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import io.akka.linkwarden.application.Data;
import io.akka.linkwarden.domain.Config;

/** Searching a caller's own links. SPEC-001 R60–R65. */
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
@HttpEndpoint("/api/v1")
public class SearchEndpoint extends Surface {

  public SearchEndpoint(Data data, Config config) {
    super(data, config);
  }

  @Get("/search")
  public HttpResponse search() {
    Caller.Result result = signedIn();
    if (result.refused()) return result.refusal();

    return Answers.enveloped(
        200,
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
                true)),
        true,
        "Success");
  }
}

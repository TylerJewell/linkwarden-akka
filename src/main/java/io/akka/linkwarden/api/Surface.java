package io.akka.linkwarden.api;

import akka.javasdk.http.AbstractHttpEndpoint;
import io.akka.linkwarden.application.Data;
import io.akka.linkwarden.domain.Config;
import java.time.Instant;
import java.util.Optional;

/**
 * What every route needs before it can answer: who is asking, and what the request carried outside
 * its body.
 *
 * <p>A query parameter is read explicitly rather than bound to a method argument, because only a
 * path parameter is bound and a method that declares one without a matching path segment silently
 * receives the type's default for every real caller.
 */
public abstract class Surface extends AbstractHttpEndpoint {

  protected final Data data;
  protected final Config config;
  protected final Caller caller;

  protected Surface(Data data, Config config) {
    this.data = data;
    this.config = config;
    this.caller = new Caller(data, config);
  }

  protected String authorization() {
    return requestContext().requestHeader("Authorization").map(header -> header.value()).orElse(null);
  }

  protected Caller.Result signedIn() {
    return caller.fromRequest(authorization(), Instant.now());
  }

  protected Caller.Result presented() {
    return caller.fromToken(authorization(), Instant.now());
  }

  protected Optional<String> query(String name) {
    return requestContext().queryParams().getString(name).filter(value -> !value.isEmpty());
  }

  protected boolean queryFlag(String name) {
    return query(name).map(value -> value.equalsIgnoreCase("true")).orElse(false);
  }

  protected Integer queryNumber(String name) {
    return query(name)
        .map(
            value -> {
              try {
                return Integer.valueOf(value);
              } catch (NumberFormatException e) {
                return null;
              }
            })
        .orElse(null);
  }
}

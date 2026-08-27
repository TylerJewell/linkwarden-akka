package io.akka.linkwarden.api;

import akka.http.javadsl.model.HttpResponse;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.http.AbstractHttpEndpoint;
import io.akka.linkwarden.domain.Config;

/** What the instance is configured with, published. SPEC-001 R1–R4. */
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
@HttpEndpoint("/api/v1")
public class ConfigEndpoint extends AbstractHttpEndpoint {

  /** Reported as the instance's version, the way the original reports the image it was built from. */
  public static final String INSTANCE_VERSION = "v2.16.1";

  private final Config config;

  public ConfigEndpoint(Config config) {
    this.config = config;
  }

  @Get("/config")
  public HttpResponse config() {
    return Answers.wrapped(200, config.published(INSTANCE_VERSION));
  }

  @Get("/logins")
  public HttpResponse logins() {
    return Answers.json(200, config.logins());
  }
}

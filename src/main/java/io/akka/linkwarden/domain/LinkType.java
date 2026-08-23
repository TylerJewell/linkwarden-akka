package io.akka.linkwarden.domain;

/** SPEC-001 R6 — what the pipeline decided a link is, from the content type it answered with. */
public enum LinkType {
  URL,
  PDF,
  IMAGE
}

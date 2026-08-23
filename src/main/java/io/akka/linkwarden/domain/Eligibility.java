package io.akka.linkwarden.domain;

/** SPEC-001 R1 and R17 — the two questions that decide whether a link is offered at all. */
public final class Eligibility {

  public static final int CURRENT_INDEX_VERSION = 1;

  private Eligibility() {}

  public static boolean awaitingPreservation(Candidate c) {
    return c.url() != null && c.lastPreserved() == null;
  }

  public static boolean awaitingIndexing(Candidate c) {
    return c.indexVersion() == null || c.indexVersion() != CURRENT_INDEX_VERSION;
  }
}

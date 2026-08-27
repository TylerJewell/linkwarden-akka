package io.akka.linkwarden.domain;

/**
 * SPEC-001 R15 and R16 — what finishing an attempt does, which is one decision with two
 * answers rather than two places that happen to agree.
 */
public final class AttemptCompletion {

  public enum Outcome {
    /** The link is still there: every format still absent is marked unavailable. */
    MARK_UNAVAILABLE,
    /** The link went while the attempt was running: its files go with it, nothing is written. */
    REMOVE_FILES
  }

  private AttemptCompletion() {}

  public static Outcome decide(AttemptSubject link) {
    return link == null || link.deleted() ? Outcome.REMOVE_FILES : Outcome.MARK_UNAVAILABLE;
  }
}

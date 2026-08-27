package io.akka.linkwarden.domain;

/**
 * The part of a link an attempt reads. SPEC-001 R48–R53.
 *
 * <p>An attempt is a decision over five facts, and taking them as a record rather than the whole
 * link keeps the decision drivable from a test and from the benchmark without a store in the way.
 */
public record AttemptSubject(
    int linkId,
    int collectionId,
    String url,
    PreservedFormats formats,
    ArchivalSettings settings,
    boolean deleted) {

  public static AttemptSubject of(
      int linkId, int collectionId, String url, PreservedFormats formats,
      ArchivalSettings settings) {
    return new AttemptSubject(linkId, collectionId, url, formats, settings, false);
  }
}

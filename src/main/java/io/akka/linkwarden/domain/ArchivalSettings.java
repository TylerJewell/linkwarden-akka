package io.akka.linkwarden.domain;

/** SPEC-001 R5 — the six switches that decide which formats an attempt tries. */
public record ArchivalSettings(
    boolean archiveAsScreenshot,
    boolean archiveAsMonolith,
    boolean archiveAsPDF,
    boolean archiveAsReadable,
    boolean archiveAsWaybackMachine,
    boolean aiTag) {

  public static final ArchivalSettings NONE =
      new ArchivalSettings(false, false, false, false, false, false);

  public ArchivalSettings or(ArchivalSettings other) {
    return new ArchivalSettings(
        archiveAsScreenshot || other.archiveAsScreenshot,
        archiveAsMonolith || other.archiveAsMonolith,
        archiveAsPDF || other.archiveAsPDF,
        archiveAsReadable || other.archiveAsReadable,
        archiveAsWaybackMachine || other.archiveAsWaybackMachine,
        aiTag || other.aiTag);
  }
}

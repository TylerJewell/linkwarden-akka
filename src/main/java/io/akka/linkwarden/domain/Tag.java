package io.akka.linkwarden.domain;

/**
 * A tag on a link. Each of the six archival fields is present or absent, and presence is what
 * makes the tag archival (SPEC-001 R50) — a tag carrying all six as {@code false} is archival
 * and switches every format off, while a tag carrying none of them is ignored.
 */
public record Tag(
    String name,
    Boolean archiveAsScreenshot,
    Boolean archiveAsMonolith,
    Boolean archiveAsPDF,
    Boolean archiveAsReadable,
    Boolean archiveAsWaybackMachine,
    Boolean aiTag) {

  public static Tag plain(String name) {
    return new Tag(name, null, null, null, null, null, null);
  }

  public boolean isArchival() {
    return archiveAsScreenshot != null
        || archiveAsMonolith != null
        || archiveAsPDF != null
        || archiveAsReadable != null
        || archiveAsWaybackMachine != null
        || aiTag != null;
  }

  ArchivalSettings asSettings() {
    return new ArchivalSettings(
        Boolean.TRUE.equals(archiveAsScreenshot),
        Boolean.TRUE.equals(archiveAsMonolith),
        Boolean.TRUE.equals(archiveAsPDF),
        Boolean.TRUE.equals(archiveAsReadable),
        Boolean.TRUE.equals(archiveAsWaybackMachine),
        Boolean.TRUE.equals(aiTag));
  }
}

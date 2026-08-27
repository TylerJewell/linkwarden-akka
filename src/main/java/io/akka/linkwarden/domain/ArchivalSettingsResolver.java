package io.akka.linkwarden.domain;

import java.util.List;

/** SPEC-001 R50 — tags decide, or the owner does; never a blend of the two. */
public final class ArchivalSettingsResolver {

  private ArchivalSettingsResolver() {}

  public static ArchivalSettings resolve(List<Tag> tags, ArchivalSettings ownerSettings) {
    List<Tag> archival = tags == null ? List.of() : tags.stream().filter(Tag::isArchival).toList();
    if (archival.isEmpty()) {
      return ownerSettings;
    }
    ArchivalSettings resolved = ArchivalSettings.NONE;
    for (Tag tag : archival) {
      resolved = resolved.or(tag.asSettings());
    }
    return resolved;
  }
}

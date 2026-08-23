package io.akka.linkwarden.domain;

import java.time.Instant;
import java.util.List;

/**
 * One saved link and everything the archiving pipeline decides about it.
 *
 * <p>Nullability here is the source's: {@code url} is absent on a link saved without one and that
 * absence is what keeps it out of the pipeline (SPEC-001 R1); each of the five formats is absent
 * until an attempt answers it; {@code lastPreserved} absent means awaiting preservation and {@code
 * indexVersion} absent means awaiting indexing. Entity state carries these as plain nullable
 * fields — a View row cannot, and does not (see {@code LinksView}).
 */
public record Link(
    String linkId,
    /** What the interface calls this link. Carried, not decided: no rule in SPEC-001 reads it. */
    String title,
    String url,
    String collectionId,
    String ownerId,
    LinkType type,
    PreservedFormats formats,
    String metaDescription,
    String textContent,
    Instant lastPreserved,
    Integer indexVersion,
    List<Tag> tags,
    ArchivalSettings ownerSettings,
    Instant createdAt,
    boolean deleted) {

  public static Link saved(
      String linkId,
      String title,
      String url,
      String collectionId,
      String ownerId,
      List<Tag> tags,
      ArchivalSettings ownerSettings,
      Instant createdAt) {
    return new Link(
        linkId,
        title,
        url,
        collectionId,
        ownerId,
        LinkType.URL,
        PreservedFormats.EMPTY,
        null,
        null,
        null,
        null,
        tags == null ? List.of() : List.copyOf(tags),
        ownerSettings,
        createdAt,
        false);
  }

  public ArchivalSettings effectiveSettings() {
    return ArchivalSettingsResolver.resolve(tags, ownerSettings);
  }

  public Link withFormats(PreservedFormats f) {
    return new Link(
        linkId, title, url, collectionId, ownerId, type, f, metaDescription, textContent, lastPreserved,
        indexVersion, tags, ownerSettings, createdAt, deleted);
  }

  public Link withType(LinkType t) {
    return new Link(
        linkId, title, url, collectionId, ownerId, t, formats, metaDescription, textContent, lastPreserved,
        indexVersion, tags, ownerSettings, createdAt, deleted);
  }

  public Link withMetaDescription(String v) {
    return new Link(
        linkId, title, url, collectionId, ownerId, type, formats, v, textContent, lastPreserved,
        indexVersion, tags, ownerSettings, createdAt, deleted);
  }

  public Link withTextContent(String v) {
    return new Link(
        linkId, title, url, collectionId, ownerId, type, formats, metaDescription, v, lastPreserved,
        indexVersion, tags, ownerSettings, createdAt, deleted);
  }

  public Link withLastPreserved(Instant v) {
    return new Link(
        linkId, title, url, collectionId, ownerId, type, formats, metaDescription, textContent, v,
        indexVersion, tags, ownerSettings, createdAt, deleted);
  }

  public Link withIndexVersion(Integer v) {
    return new Link(
        linkId, title, url, collectionId, ownerId, type, formats, metaDescription, textContent,
        lastPreserved, v, tags, ownerSettings, createdAt, deleted);
  }

  public Link deletedNow() {
    return new Link(
        linkId, title, url, collectionId, ownerId, type, formats, metaDescription, textContent,
        lastPreserved, indexVersion, tags, ownerSettings, createdAt, true);
  }
}

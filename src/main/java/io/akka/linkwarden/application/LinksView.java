package io.akka.linkwarden.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.Consume;
import akka.javasdk.annotations.Query;
import akka.javasdk.view.TableUpdater;
import akka.javasdk.view.View;
import io.akka.linkwarden.domain.Eligibility;
import io.akka.linkwarden.domain.Format;
import io.akka.linkwarden.domain.LinkType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The read side the two pipelines are fed from, and the one screen reads.
 *
 * <p>Every field that a link is genuinely without at some point in its life is {@code Optional} on
 * the row rather than a plain nullable field: a View row schema rejects a null in a non-optional
 * column the first time one is written, and the failure is silent — the update stream restarts in
 * a loop and every query against the view returns nothing. Six of the fields here are absent on a
 * link that has only just been saved, which is every link's first state.
 */
@Component(id = "links-view")
public class LinksView extends View {

  public record LinkEntry(
      String linkId,
      String title,
      Optional<String> url,
      String collectionId,
      String ownerId,
      LinkType type,
      Optional<String> image,
      Optional<String> pdf,
      Optional<String> readable,
      Optional<String> monolith,
      Optional<String> preview,
      Optional<String> metaDescription,
      Optional<String> textContent,
      Optional<Instant> lastPreserved,
      int indexVersion,
      Instant createdAt,
      boolean deleted) {}

  public record LinkRows(List<LinkEntry> links) {}

  /**
   * {@code indexVersion} is stored as {@code 0} where the link has none. Absent and zero are the
   * same answer to R17 — neither equals the current version — and an int column keeps the query
   * below expressible as a comparison rather than a null test.
   */
  static final int NOT_INDEXED = 0;

  @Consume.FromEventSourcedEntity(LinkEntity.class)
  public static class LinksUpdater extends TableUpdater<LinkEntry> {
    public Effect<LinkEntry> onEvent(LinkEntity.Event event) {
      return effects().updateRow(apply(rowState(), event));
    }

    private LinkEntry apply(LinkEntry current, LinkEntity.Event event) {
      return switch (event) {
        case LinkEntity.Saved e ->
            new LinkEntry(
                e.linkId(),
                e.title() == null ? "" : e.title(),
                Optional.ofNullable(e.url()),
                e.collectionId(),
                e.ownerId(),
                LinkType.URL,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                NOT_INDEXED,
                e.at(),
                false);
        case LinkEntity.TypeDetermined e -> copy(current, e.type());
        case LinkEntity.MetaDescriptionSet e ->
            copyMeta(current, Optional.of(e.metaDescription()), current.textContent());
        case LinkEntity.TextExtracted e ->
            copyMeta(current, current.metaDescription(), Optional.of(e.textContent()));
        case LinkEntity.FormatPreserved e -> copyFormat(current, e.format(), e.path());
        case LinkEntity.PreservationFinished e ->
            new LinkEntry(
                current.linkId(),
                current.title(),
                current.url(),
                current.collectionId(),
                current.ownerId(),
                current.type(),
                Optional.ofNullable(e.formats().image()),
                Optional.ofNullable(e.formats().pdf()),
                Optional.ofNullable(e.formats().readable()),
                Optional.ofNullable(e.formats().monolith()),
                Optional.ofNullable(e.formats().preview()),
                current.metaDescription(),
                current.textContent(),
                Optional.of(e.at()),
                NOT_INDEXED,
                current.createdAt(),
                current.deleted());
        case LinkEntity.Indexed e -> copyIndexVersion(current, e.indexVersion());
        case LinkEntity.ReArchiveRequested e ->
            new LinkEntry(
                current.linkId(),
                current.title(),
                current.url(),
                current.collectionId(),
                current.ownerId(),
                current.type(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                current.metaDescription(),
                Optional.empty(),
                Optional.empty(),
                NOT_INDEXED,
                current.createdAt(),
                current.deleted());
        case LinkEntity.Deleted e -> copyDeleted(current);
      };
    }

    private LinkEntry copy(LinkEntry r, LinkType type) {
      return new LinkEntry(
          r.linkId(), r.title(), r.url(), r.collectionId(), r.ownerId(), type, r.image(), r.pdf(),
          r.readable(), r.monolith(), r.preview(), r.metaDescription(), r.textContent(),
          r.lastPreserved(), r.indexVersion(), r.createdAt(), r.deleted());
    }

    private LinkEntry copyMeta(LinkEntry r, Optional<String> meta, Optional<String> text) {
      return new LinkEntry(
          r.linkId(), r.title(), r.url(), r.collectionId(), r.ownerId(), r.type(), r.image(), r.pdf(),
          r.readable(), r.monolith(), r.preview(), meta, text, r.lastPreserved(), r.indexVersion(),
          r.createdAt(), r.deleted());
    }

    private LinkEntry copyIndexVersion(LinkEntry r, int version) {
      return new LinkEntry(
          r.linkId(), r.title(), r.url(), r.collectionId(), r.ownerId(), r.type(), r.image(), r.pdf(),
          r.readable(), r.monolith(), r.preview(), r.metaDescription(), r.textContent(),
          r.lastPreserved(), version, r.createdAt(), r.deleted());
    }

    private LinkEntry copyDeleted(LinkEntry r) {
      return new LinkEntry(
          r.linkId(), r.title(), r.url(), r.collectionId(), r.ownerId(), r.type(), r.image(), r.pdf(),
          r.readable(), r.monolith(), r.preview(), r.metaDescription(), r.textContent(),
          r.lastPreserved(), r.indexVersion(), r.createdAt(), true);
    }

    private LinkEntry copyFormat(LinkEntry r, Format format, String path) {
      Optional<String> v = Optional.ofNullable(path);
      return switch (format) {
        case IMAGE ->
            new LinkEntry(r.linkId(), r.title(), r.url(), r.collectionId(), r.ownerId(), r.type(), v, r.pdf(),
                r.readable(), r.monolith(), r.preview(), r.metaDescription(), r.textContent(),
                r.lastPreserved(), r.indexVersion(), r.createdAt(), r.deleted());
        case PDF ->
            new LinkEntry(r.linkId(), r.title(), r.url(), r.collectionId(), r.ownerId(), r.type(), r.image(), v,
                r.readable(), r.monolith(), r.preview(), r.metaDescription(), r.textContent(),
                r.lastPreserved(), r.indexVersion(), r.createdAt(), r.deleted());
        case READABLE ->
            new LinkEntry(r.linkId(), r.title(), r.url(), r.collectionId(), r.ownerId(), r.type(), r.image(),
                r.pdf(), v, r.monolith(), r.preview(), r.metaDescription(), r.textContent(),
                r.lastPreserved(), r.indexVersion(), r.createdAt(), r.deleted());
        case MONOLITH ->
            new LinkEntry(r.linkId(), r.title(), r.url(), r.collectionId(), r.ownerId(), r.type(), r.image(),
                r.pdf(), r.readable(), v, r.preview(), r.metaDescription(), r.textContent(),
                r.lastPreserved(), r.indexVersion(), r.createdAt(), r.deleted());
        case PREVIEW ->
            new LinkEntry(r.linkId(), r.title(), r.url(), r.collectionId(), r.ownerId(), r.type(), r.image(),
                r.pdf(), r.readable(), r.monolith(), v, r.metaDescription(), r.textContent(),
                r.lastPreserved(), r.indexVersion(), r.createdAt(), r.deleted());
      };
    }
  }

  @Query("SELECT * AS links FROM links WHERE deleted = false ORDER BY createdAt DESC")
  public QueryEffect<LinkRows> all() {
    return queryResult();
  }

  @Query(
      "SELECT * AS links FROM links WHERE deleted = false AND collectionId = :collectionId"
          + " ORDER BY createdAt DESC")
  public QueryEffect<LinkRows> inCollection(String collectionId) {
    return queryResult();
  }

  /** R1 — a link with a url and no preservation yet. */
  @Query(
      "SELECT * AS links FROM links WHERE deleted = false AND lastPreserved IS NULL"
          + " AND url IS NOT NULL ORDER BY createdAt DESC")
  public QueryEffect<LinkRows> awaitingPreservation() {
    return queryResult();
  }

  /** R17 — anything not at the current index version, absent included. */
  @Query(
      "SELECT * AS links FROM links WHERE deleted = false AND indexVersion != :currentVersion"
          + " ORDER BY createdAt ASC")
  public QueryEffect<LinkRows> awaitingIndexing(int currentVersion) {
    return queryResult();
  }

  public static int currentIndexVersion() {
    return Eligibility.CURRENT_INDEX_VERSION;
  }
}

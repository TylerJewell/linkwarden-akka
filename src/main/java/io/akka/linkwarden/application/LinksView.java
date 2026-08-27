package io.akka.linkwarden.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.Consume;
import akka.javasdk.annotations.Query;
import akka.javasdk.view.TableUpdater;
import akka.javasdk.view.View;
import io.akka.linkwarden.domain.Eligibility;
import io.akka.linkwarden.domain.Records;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The read side every list, search and pipeline is fed from.
 *
 * <p>A link's own record is what the row carries; the collection's name, owner and members are
 * not, because a member added to a collection would otherwise have to be written into every one of
 * its links. The endpoints join the two, which keeps a permission answer read from one place.
 *
 * <p>{@code indexVersion} is stored as {@code 0} where the link has none: absent and zero are the
 * same answer to R57 — neither equals the current version — and an int column keeps the query a
 * comparison rather than a null test.
 */
@Component(id = "links-view")
public class LinksView extends View {

  static final int NOT_INDEXED = 0;

  public record LinkRow(
      int id,
      String name,
      String type,
      String description,
      int collectionId,
      Optional<Integer> createdById,
      Optional<String> url,
      Optional<String> image,
      Optional<String> pdf,
      Optional<String> readable,
      Optional<String> monolith,
      Optional<String> preview,
      Optional<String> metaDescription,
      Optional<String> icon,
      Optional<String> iconWeight,
      Optional<String> color,
      boolean clientSide,
      boolean aiTagged,
      int indexVersion,
      Optional<Instant> lastPreserved,
      Optional<Instant> importDate,
      List<Integer> tagIds,
      List<Integer> pinnedBy,
      Instant createdAt,
      Instant updatedAt,
      boolean deleted) {}

  public record LinkRows(List<LinkRow> links) {}

  @Consume.FromKeyValueEntity(LinkEntity.class)
  public static class LinksUpdater extends TableUpdater<LinkRow> {
    public Effect<LinkRow> onChange(Records.Link link) {
      return effects().updateRow(rowOf(link));
    }
  }

  /** A link as a row. Shared so that a row read from the entity and one read here are one shape. */
  public static LinkRow rowOf(Records.Link link) {
    return new LinkRow(
        link.id(),
        link.name(),
        link.type(),
        link.description(),
        link.collectionId(),
        Optional.ofNullable(link.createdById()),
        Optional.ofNullable(link.url()),
        Optional.ofNullable(link.image()),
        Optional.ofNullable(link.pdf()),
        Optional.ofNullable(link.readable()),
        Optional.ofNullable(link.monolith()),
        Optional.ofNullable(link.preview()),
        Optional.ofNullable(link.metaDescription()),
        Optional.ofNullable(link.icon()),
        Optional.ofNullable(link.iconWeight()),
        Optional.ofNullable(link.color()),
        link.clientSide(),
        link.aiTagged(),
        link.indexVersion() == null ? NOT_INDEXED : link.indexVersion(),
        Optional.ofNullable(link.lastPreserved()),
        Optional.ofNullable(link.importDate()),
        link.tagIds(),
        link.pinnedBy(),
        link.createdAt(),
        link.updatedAt(),
        link.deleted());
  }

  public static int currentIndexVersion() {
    return Eligibility.CURRENT_INDEX_VERSION;
  }

  @Query("SELECT * AS links FROM links WHERE deleted = false ORDER BY id DESC")
  public QueryEffect<LinkRows> all() {
    return queryResult();
  }

  /** SPEC-001 R48 — a url and nothing preserved yet. */
  @Query(
      "SELECT * AS links FROM links"
          + " WHERE deleted = false AND url IS NOT NULL AND lastPreserved IS NULL"
          + " ORDER BY id ASC")
  public QueryEffect<LinkRows> awaitingPreservation() {
    return queryResult();
  }

  /** SPEC-001 R57 — an index version that is not the one this instance writes. */
  @Query(
      "SELECT * AS links FROM links"
          + " WHERE deleted = false AND indexVersion != :indexVersion ORDER BY id ASC")
  public QueryEffect<LinkRows> awaitingIndexing(int indexVersion) {
    return queryResult();
  }
}

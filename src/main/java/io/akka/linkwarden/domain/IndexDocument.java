package io.akka.linkwarden.domain;

import java.util.List;

/**
 * What is written to the search index for one link. SPEC-001 R58.
 *
 * <p>The eleven filterable names are fixed and are exactly the fields a query in R61's language
 * can filter on, so the document and the query language are one decision: a field the document
 * does not carry is a filter the search cannot honour, whichever engine is behind it.
 */
public record IndexDocument(
    int id,
    String name,
    String url,
    String description,
    String type,
    String textContent,
    int collectionId,
    int collectionOwnerId,
    List<Integer> collectionMemberIds,
    boolean collectionIsPublic,
    String collectionName,
    List<String> tags,
    List<Integer> pinnedBy,
    long creationTimestamp,
    int indexVersion) {

  /** The attributes a filter may name, in the order the index declares them. */
  public static final List<String> FILTERABLE =
      List.of(
          "collectionOwnerId",
          "collectionMemberIds",
          "collectionName",
          "tags",
          "pinnedBy",
          "url",
          "type",
          "name",
          "description",
          "collectionIsPublic",
          "creationTimestamp");

  /** The two attributes a search may order by. */
  public static final List<String> SORTABLE = List.of("id", "name");
}

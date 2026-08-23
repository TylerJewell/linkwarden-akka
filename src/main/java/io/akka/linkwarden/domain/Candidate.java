package io.akka.linkwarden.domain;

import java.time.Instant;

/**
 * One row as a batch selector sees it. The selectors decide over this rather than over the whole
 * link, because what they read is exactly these fields.
 */
public record Candidate(
    String linkId,
    String ownerId,
    String url,
    Instant createdAt,
    Instant lastPreserved,
    Instant ownerLastPickedAt,
    Integer indexVersion) {}

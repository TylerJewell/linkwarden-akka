package io.akka.linkwarden.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * SPEC-001 R2-R4 — how one archiving batch is shared between owners.
 *
 * <p>The result carries the stamped owners alongside the picked links because R4 stamps every
 * owner who had an eligible link, including ones whose links did not make the batch: keeping the
 * two in one record is what stops the second being derived from the first.
 */
public final class BatchSelection {

  public record Batch(List<String> linkIds, List<String> stampedOwnerIds) {}

  private BatchSelection() {}

  public static Batch pick(List<Candidate> candidates, int maxBatchLinks) {
    if (maxBatchLinks <= 0) {
      return new Batch(List.of(), List.of());
    }

    List<Candidate> eligible =
        candidates.stream().filter(Eligibility::awaitingPreservation).toList();
    if (eligible.isEmpty()) {
      return new Batch(List.of(), List.of());
    }

    // R2 — owners ordered by when they were last picked, never-picked first, then by id.
    Map<String, Instant> ownerLastPicked = new LinkedHashMap<>();
    for (Candidate c : eligible) {
      ownerLastPicked.putIfAbsent(c.ownerId(), c.ownerLastPickedAt());
    }
    List<String> owners =
        ownerLastPicked.keySet().stream()
            .sorted(
                Comparator.comparing(
                        (String o) -> ownerLastPicked.get(o),
                        Comparator.nullsFirst(Comparator.naturalOrder()))
                    .thenComparing(Comparator.naturalOrder()))
            .limit(maxBatchLinks)
            .toList();

    // R3 — newest-created first within an owner.
    Map<String, List<Candidate>> byOwner = new LinkedHashMap<>();
    for (String owner : owners) {
      byOwner.put(
          owner,
          eligible.stream()
              .filter(c -> c.ownerId().equals(owner))
              .sorted(Comparator.comparing(Candidate::createdAt).reversed())
              .toList());
    }

    int linksPerOwner = Math.max(1, maxBatchLinks / owners.size());
    Map<String, Integer> offset = new LinkedHashMap<>();
    owners.forEach(o -> offset.put(o, 0));

    Set<String> picked = new LinkedHashSet<>();
    while (picked.size() < maxBatchLinks) {
      int addedThisPass = 0;
      for (String owner : owners) {
        if (picked.size() >= maxBatchLinks) {
          break;
        }
        int toTake = Math.min(linksPerOwner, maxBatchLinks - picked.size());
        if (toTake <= 0) {
          break;
        }
        List<Candidate> ownerLinks = byOwner.get(owner);
        int skip = offset.get(owner);
        if (skip >= ownerLinks.size()) {
          continue;
        }
        List<Candidate> slice = ownerLinks.subList(skip, Math.min(skip + toTake, ownerLinks.size()));
        offset.put(owner, skip + slice.size());
        for (Candidate c : slice) {
          if (picked.size() >= maxBatchLinks) {
            break;
          }
          if (picked.add(c.linkId())) {
            addedThisPass++;
          }
        }
      }
      if (addedThisPass == 0) {
        break;
      }
    }

    // R4 — stamped by having had an eligible link, not by having contributed to the batch.
    List<String> stamped = new ArrayList<>(owners);
    return new Batch(List.copyOf(picked), List.copyOf(stamped));
  }
}

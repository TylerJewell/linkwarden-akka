package io.akka.linkwarden.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * SPEC-001 R57 — half the batch oldest-first and half newest-first, duplicates dropped by id.
 * A batch of one therefore holds only the newest, and a batch asking for more than exists holds
 * the overlap once.
 */
public final class IndexBatch {

  private IndexBatch() {}

  public static List<String> pick(List<Candidate> candidates, int take) {
    List<Candidate> eligible = candidates.stream().filter(Eligibility::awaitingIndexing).toList();
    if (take <= 0 || eligible.isEmpty()) {
      return List.of();
    }

    // The source orders by the row's own primary key, which is its insertion order; here
    // that is creation time, with the id breaking a tie.
    List<Candidate> oldToNew =
        eligible.stream()
            .sorted(Comparator.comparing(Candidate::createdAt).thenComparing(Candidate::linkId))
            .toList();
    List<Candidate> newToOld = new ArrayList<>(oldToNew).reversed();

    int firstTake = take / 2;
    int secondTake = take - firstTake;

    List<String> picked = new ArrayList<>();
    oldToNew.stream().limit(firstTake).forEach(c -> picked.add(c.linkId()));
    newToOld.stream().limit(secondTake).forEach(c -> picked.add(c.linkId()));

    return List.copyOf(new LinkedHashSet<>(picked));
  }
}

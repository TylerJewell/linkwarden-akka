package io.akka.linkwarden.bench;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.akka.linkwarden.application.AttemptRunner;
import io.akka.linkwarden.domain.ArchivalSettings;
import io.akka.linkwarden.domain.AttemptCompletion;
import io.akka.linkwarden.domain.BatchSelection;
import io.akka.linkwarden.domain.Candidate;
import io.akka.linkwarden.domain.Format;
import io.akka.linkwarden.domain.IndexBatch;
import io.akka.linkwarden.domain.Link;
import io.akka.linkwarden.domain.PageFacts;
import io.akka.linkwarden.domain.PreservedFormats;
import io.akka.linkwarden.domain.Tag;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Runs `linkwarden-port/bench/workloads.json` through this port and writes
 * `linkwarden-port/bench/port-answers.json` beside the source's own answers.
 *
 * <p>Written as a test so it builds and runs with everything else; it asserts only that it
 * produced an answer for every workload, because the comparison itself is `answer_diff.py`'s.
 */
public class BenchmarkRunner {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Path BENCH = Path.of("..", "linkwarden-port", "bench");

  // Both sides name the link and its collection rather than numbering them, so the paths in
  // the two answer files are comparable without either side's identifiers leaking in.
  private static final String LINK_ID = "L";
  private static final String COLLECTION_ID = "C";

  @Test
  public void runBenchmark() throws Exception {
    ArrayNode workloads = (ArrayNode) JSON.readTree(BENCH.resolve("workloads.json").toFile());
    ObjectNode out = JSON.createObjectNode();
    ObjectNode answers = out.putObject("answers");
    ObjectNode timings = JSON.createObjectNode();

    for (JsonNode w : workloads) {
      String name = w.get("name").asText();
      switch (w.get("kind").asText()) {
        case "attempt" -> answers.set(name, attempt(link(w), facts(w.get("facts"))));
        case "sequence" -> answers.set(name, sequence(w));
        case "eligibility" -> answers.set(name, eligibility(w));
        case "completion" -> answers.set(name, completion(w));
        case "index-drain" -> answers.set(name, indexDrain(w));
        case "index-batch" -> answers.set(name, indexBatch(w));
        case "owner-batch" -> answers.set(name, ownerBatch(w));
        default -> throw new IllegalArgumentException("unknown workload kind");
      }
    }

    // One figure per workload, each timed over the whole set so no call is loop-invariant.
    for (JsonNode w : workloads) {
      if (w.get("kind").asText().equals("attempt")) {
        timings.set(w.get("name").asText(), timeOne(w, workloads));
      }
    }

    Files.createDirectories(BENCH);
    Files.writeString(
        BENCH.resolve("port-answers.json"),
        JSON.writerWithDefaultPrettyPrinter().writeValueAsString(out));
    ObjectNode timingFile = JSON.createObjectNode();
    timingFile.set("timing", timings);
    Files.writeString(
        BENCH.resolve("port-timings.json"),
        JSON.writerWithDefaultPrettyPrinter().writeValueAsString(timingFile));
    System.out.println("written " + BENCH.resolve("port-answers.json").toAbsolutePath());
    if (answers.size() != workloads.size()) {
      throw new AssertionError("a workload produced no answer");
    }
  }

  // ------------------------------------------------------------------ workloads

  private ObjectNode attempt(Link link, PageFacts facts) {
    var outcome = AttemptRunner.run(link, facts);
    return answerOf(apply(link, outcome), outcome);
  }

  private ArrayNode sequence(JsonNode w) {
    Link link = link(w);
    ArrayNode steps = JSON.createArrayNode();
    for (JsonNode step : w.get("steps")) {
      if (step.get("reArchiveFirst").asBoolean()) {
        link = link.withFormats(PreservedFormats.EMPTY).withTextContent(null)
            .withLastPreserved(null).withIndexVersion(null);
      }
      var outcome = AttemptRunner.run(link, facts(step.get("facts")));
      link = apply(link, outcome);
      ObjectNode entry = steps.addObject();
      entry.put("step", step.get("name").asText());
      entry.put("outcome", outcomeOf(link, outcome));
      entry.set("answer", answerOf(link, outcome));
      // The attempt has finished, so every format still absent reads unavailable (R15).
      link = link.withFormats(link.formats().markAbsentUnavailable())
          .withLastPreserved(Instant.parse("2026-01-02T00:00:00Z")).withIndexVersion(null);
    }
    return steps;
  }

  private ObjectNode eligibility(JsonNode w) {
    ObjectNode node = JSON.createObjectNode();
    for (JsonNode c : w.get("cases")) {
      var candidate =
          new Candidate(
              "L", "O1", text(c, "url"), Instant.parse("2026-01-01T00:00:00Z"),
              c.get("lastPreserved").isNull() ? null : Instant.parse(text(c, "lastPreserved")),
              null, null);
      var batch = BatchSelection.pick(List.of(candidate), 5);
      node.put(c.get("name").asText(), batch.linkIds().isEmpty() ? "not picked" : "picked");
    }
    return node;
  }

  private ObjectNode completion(JsonNode w) {
    ObjectNode node = JSON.createObjectNode();
    for (JsonNode c : w.get("cases")) {
      Link link =
          Link.saved("L", "A link", "https://example.test/a", COLLECTION_ID, "O1", List.of(),
              ArchivalSettings.NONE, Instant.parse("2026-01-01T00:00:00Z"));
      if (c.get("deleted").asBoolean()) {
        link = link.deletedNow();
      }
      node.put(
          c.get("name").asText(),
          AttemptCompletion.decide(link) == AttemptCompletion.Outcome.REMOVE_FILES
              ? "files removed"
              : "marked unavailable");
    }
    return node;
  }

  /** The same rows drained to empty in batches of `take`: what each batch held, in order. */
  private ArrayNode indexDrain(JsonNode w) {
    List<Candidate> rows = new ArrayList<>();
    for (int i = 1; i <= w.get("rows").asInt(); i++) {
      rows.add(
          new Candidate(
              String.format("%02d", i), "O1", "u",
              Instant.parse("2026-01-01T00:00:00Z").plusSeconds(i), null, null, null));
    }
    ArrayNode drained = JSON.createArrayNode();
    while (true) {
      List<String> batch = IndexBatch.pick(rows, w.get("take").asInt());
      if (batch.isEmpty()) {
        break;
      }
      ArrayNode entry = drained.addArray();
      batch.forEach(id -> entry.add("L" + id));
      List<Candidate> remaining = new ArrayList<>();
      for (Candidate c : rows) {
        remaining.add(
            batch.contains(c.linkId())
                ? new Candidate(c.linkId(), c.ownerId(), c.url(), c.createdAt(),
                    c.lastPreserved(), c.ownerLastPickedAt(), 1)
                : c);
      }
      rows = remaining;
    }
    return drained;
  }

  private ObjectNode indexBatch(JsonNode w) {
    List<Candidate> rows = new ArrayList<>();
    for (int i = 1; i <= w.get("rows").asInt(); i++) {
      rows.add(
          new Candidate(
              String.format("%02d", i), "O1", "u",
              Instant.parse("2026-01-01T00:00:00Z").plusSeconds(i), null, null, null));
    }
    ObjectNode node = JSON.createObjectNode();
    for (JsonNode take : w.get("takes")) {
      ArrayNode picked = node.putArray("take=" + take.asInt());
      IndexBatch.pick(rows, take.asInt()).forEach(id -> picked.add("L" + id));
    }
    return node;
  }

  private ObjectNode ownerBatch(JsonNode w) {
    ObjectNode node = JSON.createObjectNode();
    for (JsonNode shape : w.get("shapes")) {
      List<Candidate> rows = new ArrayList<>();
      int id = 1;
      int owner = 1;
      for (JsonNode count : shape.get("links")) {
        for (int i = 0; i < count.asInt(); i++) {
          id++;
          rows.add(
              new Candidate(
                  String.valueOf(id - 1), "O" + owner, "u",
                  Instant.parse("2026-01-01T00:00:00Z").plusSeconds(id), null, null, null));
        }
        owner++;
      }
      var batch = BatchSelection.pick(rows, shape.get("batch").asInt());
      ObjectNode entry = node.putObject(shape.get("name").asText());
      ArrayNode picked = entry.putArray("picked");
      ArrayNode owners = entry.putArray("owners");
      for (String linkId : batch.linkIds()) {
        picked.add("L" + linkId);
        rows.stream()
            .filter(c -> c.linkId().equals(linkId))
            .findFirst()
            .ifPresent(c -> owners.add(c.ownerId()));
      }
      ArrayNode stamped = entry.putArray("stamped");
      batch.stampedOwnerIds().forEach(stamped::add);
    }
    return node;
  }

  // ------------------------------------------------------------------- answers

  private Link apply(Link link, AttemptRunner.Outcome outcome) {
    Link current = link;
    for (AttemptRunner.Write write : outcome.writes()) {
      current =
          switch (write) {
            case AttemptRunner.Write.Type w -> current.withType(w.type());
            case AttemptRunner.Write.Meta w ->
                current.withMetaDescription(
                    io.akka.linkwarden.domain.MetaDescription.clip(w.description()));
            case AttemptRunner.Write.Text w -> current.withTextContent(w.text());
            case AttemptRunner.Write.Preserved w ->
                current.withFormats(current.formats().with(w.format(), w.path()));
          };
    }
    return current;
  }

  private ObjectNode answerOf(Link after, AttemptRunner.Outcome outcome) {
    // R15 — the attempt has ended, so what a reader sees is the marked state.
    PreservedFormats marked = after.formats().markAbsentUnavailable();
    ObjectNode node = JSON.createObjectNode();
    if (outcome.failedAfter() == null) {
      node.putNull("failed");
    } else {
      node.put("failed", outcome.failedAfter());
    }
    node.put("type", after.type().name().toLowerCase());
    node.put("image", marked.image());
    node.put("pdf", marked.pdf());
    node.put("readable", marked.readable());
    node.put("monolith", marked.monolith());
    node.put("preview", marked.preview());
    if (after.metaDescription() == null) {
      node.putNull("metaDescriptionLength");
    } else {
      node.put("metaDescriptionLength", after.metaDescription().length());
    }
    if (after.textContent() == null) {
      node.putNull("textContentLength");
    } else {
      node.put("textContentLength", after.textContent().length());
    }
    node.put("lastPreservedSet", true);
    node.putNull("indexVersion");
    ArrayNode wrote = node.putArray("wrote");
    for (AttemptRunner.Write write : outcome.writes()) {
      if (write instanceof AttemptRunner.Write.Preserved w) {
        wrote.add(w.path());
      }
    }
    return node;
  }

  private String outcomeOf(Link after, AttemptRunner.Outcome outcome) {
    List<String> filled = new ArrayList<>();
    for (Format f : Format.values()) {
      String v = after.formats().get(f);
      if (v != null && !v.equals(PreservedFormats.UNAVAILABLE)) {
        filled.add(f.name().toLowerCase());
      }
    }
    return (outcome.failedAfter() != null ? "failed" : "ran")
        + ":"
        + (filled.isEmpty() ? "none" : String.join("+", filled));
  }

  // -------------------------------------------------------------------- timing

  /**
   * A window sized from a pilot, five windows, the median reported. The loop cycles over every
   * workload's real inputs and accumulates the result: a call with unchanging arguments whose
   * answer nothing reads is free to be folded away, and a JIT that folds it reports zero.
   */
  private ObjectNode timeOne(JsonNode w, ArrayNode all) {
    List<Link> links = new ArrayList<>();
    List<PageFacts> factsList = new ArrayList<>();
    for (JsonNode other : all) {
      if (other.get("kind").asText().equals("attempt")) {
        links.add(link(other));
        factsList.add(facts(other.get("facts")));
      }
    }
    Link subject = link(w);
    PageFacts subjectFacts = facts(w.get("facts"));

    long sink = 0;
    for (int i = 0; i < 20_000; i++) {
      sink += AttemptRunner.run(subject, subjectFacts).writes().size();
    }

    long pilotStart = System.nanoTime();
    for (int i = 0; i < 1000; i++) {
      sink += AttemptRunner.run(subject, subjectFacts).writes().size();
    }
    double perOp = (System.nanoTime() - pilotStart) / 1000.0;
    int reps = (int) Math.max(1000, Math.ceil(50_000_000 / Math.max(perOp, 1)));

    long[] windows = new long[5];
    for (int win = 0; win < 5; win++) {
      long start = System.nanoTime();
      for (int i = 0; i < reps; i++) {
        // Cycling the arguments keeps the call from being hoisted out as loop-invariant;
        // the subject's own facts are used every other iteration so the figure is its own.
        Link l = (i % 2 == 0) ? subject : links.get(i % links.size());
        PageFacts f = (i % 2 == 0) ? subjectFacts : factsList.get(i % factsList.size());
        sink += AttemptRunner.run(l, f).writes().size();
      }
      windows[win] = (System.nanoTime() - start) / reps;
    }
    java.util.Arrays.sort(windows);

    if (sink == Long.MIN_VALUE) {
      throw new AssertionError("unreachable, and the reason the loop above cannot be removed");
    }
    ObjectNode node = JSON.createObjectNode();
    node.put("repetitions", reps);
    node.put("windows", 5);
    node.put("windowNanos", windows[2] * reps);
    node.put("nanosPerRun", windows[2]);
    return node;
  }

  // ------------------------------------------------------------------- fixtures

  private Link link(JsonNode w) {
    JsonNode l = w.get("link");
    List<Tag> tags = new ArrayList<>();
    for (JsonNode t : l.get("tags")) {
      tags.add(
          new Tag(
              t.get("name").asText(),
              bool(t, "archiveAsScreenshot"),
              bool(t, "archiveAsMonolith"),
              bool(t, "archiveAsPDF"),
              bool(t, "archiveAsReadable"),
              bool(t, "archiveAsWaybackMachine"),
              bool(t, "aiTag")));
    }
    JsonNode o = w.get("owner");
    var owner =
        new ArchivalSettings(
            o.get("archiveAsScreenshot").asBoolean(),
            o.get("archiveAsMonolith").asBoolean(),
            o.get("archiveAsPDF").asBoolean(),
            o.get("archiveAsReadable").asBoolean(),
            o.get("archiveAsWaybackMachine").asBoolean(),
            o.get("aiTag").asBoolean());

    JsonNode f = l.get("formats");
    var formats =
        new PreservedFormats(
            text(f, "image"), text(f, "pdf"), text(f, "readable"), text(f, "monolith"),
            text(f, "preview"));

    return Link.saved(
            LINK_ID, "A link", l.get("url").asText(), COLLECTION_ID, "O1", tags, owner,
            Instant.parse("2026-01-01T00:00:00Z"))
        .withFormats(formats);
  }

  private PageFacts facts(JsonNode f) {
    return new PageFacts(
        text(f, "contentType"),
        text(f, "ogImage"),
        text(f, "pageOrigin"),
        text(f, "metaDescription"),
        text(f, "extractedText"),
        f.get("previewDecodedBytes").asLong(),
        f.get("screenshotBytes").asLong(),
        f.get("pdfBytes").asLong(),
        f.get("pageLoadFails").asBoolean(),
        f.get("monolithFails").asBoolean(),
        f.get("preservationDisabled").asBoolean(),
        f.get("urlIsUnsafe").asBoolean());
  }

  private static String text(JsonNode n, String field) {
    JsonNode v = n.get(field);
    return v == null || v.isNull() ? null : v.asText();
  }

  private static Boolean bool(JsonNode n, String field) {
    JsonNode v = n.get(field);
    return v == null || v.isNull() ? null : v.asBoolean();
  }
}

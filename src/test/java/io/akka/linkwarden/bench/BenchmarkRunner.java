package io.akka.linkwarden.bench;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.akka.linkwarden.application.AttemptRunner;
import io.akka.linkwarden.domain.ArchivalSettings;
import io.akka.linkwarden.domain.ArchivalSettingsResolver;
import io.akka.linkwarden.domain.AttemptSubject;
import io.akka.linkwarden.domain.BatchSelection;
import io.akka.linkwarden.domain.Candidate;
import io.akka.linkwarden.domain.Eligibility;
import io.akka.linkwarden.domain.Format;
import io.akka.linkwarden.domain.IndexBatch;
import io.akka.linkwarden.domain.PageFacts;
import io.akka.linkwarden.domain.PreservedFormats;
import io.akka.linkwarden.domain.Tag;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The rebuild's side of the preservation and indexing comparison. SPEC-001 R48–R53, R57–R59.
 *
 * <p>It reads `bench/pipeline-workloads.json` and answers each workload the way
 * `probes/source_probe/run_source.ts` answers it from linkwarden's own code, so that the two
 * files can be compared field by field. It asserts nothing: the comparison is between the two
 * answer files, not inside either of them, and a class that ran on every build would rewrite
 * them from a workload file that does not travel with the published repository.
 *
 * <pre>
 * mvn -q test-compile exec:java -Dexec.classpathScope=test \
 *     -Dexec.mainClass=io.akka.linkwarden.bench.BenchmarkRunner
 * </pre>
 */
public final class BenchmarkRunner {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** The names both sides use for the one link and the one collection a workload has. */
  private static final int LINK = 1;
  private static final int COLLECTION = 7;

  private BenchmarkRunner() {}

  public static void main(String[] args) throws Exception {
    Path bench = Path.of("..", "linkwarden-port", "bench");
    JsonNode workloads = MAPPER.readTree(bench.resolve("pipeline-workloads.json").toFile());

    ObjectNode answers = MAPPER.createObjectNode();
    ObjectNode timing = MAPPER.createObjectNode();
    ObjectNode perWorkload = MAPPER.createObjectNode();

    for (JsonNode workload : workloads) {
      String name = workload.get("name").asText();
      switch (workload.get("kind").asText()) {
        case "attempt" -> {
          answers.set(name, attempt(workload, workload.get("facts")));
          perWorkload.set(name, time(workload));
        }
        case "sequence" -> answers.set(name, sequence(workload));
        case "index-drain" -> answers.set(name, indexDrain(workload));
        case "index-batch" -> answers.set(name, indexBatch(workload));
        case "owner-batch" -> answers.set(name, ownerBatch(workload));
        case "eligibility" -> answers.set(name, eligibility(workload));
        case "completion" -> answers.set(name, completion(workload));
        default -> throw new IllegalArgumentException("no such workload kind in " + name);
      }
    }

    timing.set("as decided", perWorkload);
    ObjectNode out = MAPPER.createObjectNode();
    out.set("answers", answers);
    Files.writeString(bench.resolve("pipeline-port-answers.json"),
        MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(out));
    ObjectNode timings = MAPPER.createObjectNode();
    timings.set("timing", timing);
    Files.writeString(bench.resolve("pipeline-port-timings.json"),
        MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(timings));
    System.err.println("written pipeline-port-answers.json");
  }

  // ------------------------------------------------------------------
  // one attempt
  // ------------------------------------------------------------------

  /** The state a link is in, which an attempt reads and writes back to. */
  private static final class Row {
    String type = "url";
    String image;
    String pdf;
    String readable;
    String monolith;
    String preview;
    String metaDescription;
    String textContent;
    Instant lastPreserved;
    Integer indexVersion;
    final List<String> wrote = new ArrayList<>();
  }

  private static Row seed(JsonNode workload) {
    Row row = new Row();
    JsonNode formats = workload.get("link").get("formats");
    row.image = text(formats, "image");
    row.pdf = text(formats, "pdf");
    row.readable = text(formats, "readable");
    row.monolith = text(formats, "monolith");
    row.preview = text(formats, "preview");
    return row;
  }

  private static ObjectNode attempt(JsonNode workload, JsonNode facts) {
    return run(workload, facts, seed(workload));
  }

  private static ObjectNode run(JsonNode workload, JsonNode facts, Row row) {
    row.wrote.clear();
    AttemptSubject subject =
        AttemptSubject.of(LINK, COLLECTION, workload.get("link").get("url").asText(),
            new PreservedFormats(row.image, row.pdf, row.readable, row.monolith, row.preview),
            settings(workload));
    AttemptRunner.Outcome outcome = AttemptRunner.run(subject, pageFacts(facts));

    for (AttemptRunner.Write write : outcome.writes()) {
      switch (write) {
        case AttemptRunner.Write.Type type -> row.type = type.type().name().toLowerCase();
        case AttemptRunner.Write.Meta meta -> row.metaDescription = meta.description();
        case AttemptRunner.Write.Text text -> row.textContent = text.text();
        case AttemptRunner.Write.Preserved preserved -> {
          apply(row, preserved.format(), preserved.path());
          row.wrote.add(normalise(preserved.path()));
        }
      }
    }
    // R52-R53 — the end of an attempt, whatever the outcome.
    if (row.image == null) row.image = "unavailable";
    if (row.pdf == null) row.pdf = "unavailable";
    if (row.readable == null) row.readable = "unavailable";
    if (row.monolith == null) row.monolith = "unavailable";
    if (row.preview == null) row.preview = "unavailable";
    row.lastPreserved = Instant.parse("2026-01-01T00:00:00Z");
    row.indexVersion = null;

    ObjectNode answer = MAPPER.createObjectNode();
    answer.put("failed", outcome.failedAfter());
    answer.put("type", row.type);
    answer.put("image", normalise(row.image));
    answer.put("pdf", normalise(row.pdf));
    answer.put("readable", normalise(row.readable));
    answer.put("monolith", normalise(row.monolith));
    answer.put("preview", normalise(row.preview));
    answer.put("metaDescriptionLength",
        row.metaDescription == null ? null : row.metaDescription.length());
    answer.put("textContentLength",
        row.textContent == null ? null : row.textContent.length());
    answer.put("lastPreservedSet", row.lastPreserved != null);
    answer.put("indexVersion", row.indexVersion);
    ArrayNode wrote = answer.putArray("wrote");
    row.wrote.forEach(wrote::add);
    return answer;
  }

  private static void apply(Row row, Format format, String path) {
    switch (format) {
      case IMAGE -> row.image = path;
      case PDF -> row.pdf = path;
      case READABLE -> row.readable = path;
      case MONOLITH -> row.monolith = path;
      case PREVIEW -> row.preview = path;
    }
  }

  /** Both sides name the one link and the one collection rather than numbering them. */
  private static String normalise(String path) {
    if (path == null) return null;
    return path.replace("/" + COLLECTION + "/", "/C/")
        .replace("/" + LINK + ".", "/L.")
        .replace("/" + LINK + "_", "/L_");
  }

  private static ArchivalSettings settings(JsonNode workload) {
    JsonNode owner = workload.get("owner");
    ArchivalSettings ownerSettings =
        new ArchivalSettings(
            owner.get("archiveAsScreenshot").asBoolean(),
            owner.get("archiveAsMonolith").asBoolean(),
            owner.get("archiveAsPDF").asBoolean(),
            owner.get("archiveAsReadable").asBoolean(),
            owner.get("archiveAsWaybackMachine").asBoolean(),
            owner.get("aiTag").asBoolean());

    List<Tag> tags = new ArrayList<>();
    for (JsonNode node : workload.get("link").get("tags")) {
      tags.add(new Tag(node.get("name").asText(),
          flag(node, "archiveAsScreenshot"), flag(node, "archiveAsMonolith"),
          flag(node, "archiveAsPDF"), flag(node, "archiveAsReadable"),
          flag(node, "archiveAsWaybackMachine"), flag(node, "aiTag")));
    }
    return ArchivalSettingsResolver.resolve(tags, ownerSettings);
  }

  private static PageFacts pageFacts(JsonNode facts) {
    return new PageFacts(
        text(facts, "contentType"), text(facts, "ogImage"), text(facts, "pageOrigin"),
        text(facts, "metaDescription"), text(facts, "extractedText"),
        facts.get("previewDecodedBytes").asLong(), facts.get("screenshotBytes").asLong(),
        facts.get("pdfBytes").asLong(), facts.get("pageLoadFails").asBoolean(),
        facts.get("monolithFails").asBoolean(),
        facts.get("preservationDisabled").asBoolean(), facts.get("urlIsUnsafe").asBoolean());
  }

  private static String text(JsonNode node, String field) {
    JsonNode found = node.get(field);
    return found == null || found.isNull() ? null : found.asText();
  }

  private static Boolean flag(JsonNode node, String field) {
    JsonNode found = node.get(field);
    return found == null || found.isNull() ? null : found.asBoolean();
  }

  // ------------------------------------------------------------------
  // the other six kinds
  // ------------------------------------------------------------------

  private static ArrayNode sequence(JsonNode workload) {
    ArrayNode steps = MAPPER.createArrayNode();
    Row row = seed(workload);
    for (JsonNode step : workload.get("steps")) {
      if (step.path("reArchiveFirst").asBoolean()) {
        row.image = row.pdf = row.readable = row.monolith = row.preview = null;
        row.lastPreserved = null;
        row.indexVersion = null;
      }
      ObjectNode answer = run(workload, step.get("facts"), row);
      ObjectNode entry = MAPPER.createObjectNode();
      entry.put("step", step.get("name").asText());
      entry.put("outcome", outcomeOf(answer));
      entry.set("answer", answer);
      steps.add(entry);
    }
    return steps;
  }

  /** One short string per step, so a sequence's answers can be seen to move. */
  private static String outcomeOf(ObjectNode answer) {
    List<String> filled = new ArrayList<>();
    for (String field : List.of("image", "pdf", "readable", "monolith", "preview")) {
      String value = answer.get(field).isNull() ? null : answer.get(field).asText();
      if (value != null && !value.equals("unavailable")) filled.add(field);
    }
    return (answer.get("failed").isNull() ? "ran" : "failed") + ":"
        + (filled.isEmpty() ? "none" : String.join("+", filled));
  }

  private static ArrayNode indexDrain(JsonNode workload) {
    int rows = workload.get("rows").asInt();
    int take = workload.get("take").asInt();
    Map<String, Integer> version = new LinkedHashMap<>();
    List<Candidate> all = new ArrayList<>();
    for (int i = 1; i <= rows; i++) {
      all.add(new Candidate(name(i), "O1", "u",
          Instant.parse("2026-01-01T00:00:00Z").plusSeconds(i), null, null, null));
      version.put(name(i), null);
    }

    ArrayNode drained = MAPPER.createArrayNode();
    while (true) {
      List<Candidate> waiting = all.stream()
          .filter(c -> version.get(c.linkId()) == null)
          .toList();
      List<String> batch = IndexBatch.pick(waiting, take);
      if (batch.isEmpty()) break;
      ArrayNode row = drained.addArray();
      batch.forEach(id -> {
        row.add(id);
        version.put(id, Eligibility.CURRENT_INDEX_VERSION);
      });
    }
    return drained;
  }

  private static ObjectNode indexBatch(JsonNode workload) {
    int rows = workload.get("rows").asInt();
    ObjectNode byTake = MAPPER.createObjectNode();
    for (JsonNode take : workload.get("takes")) {
      List<Candidate> all = new ArrayList<>();
      for (int i = 1; i <= rows; i++) {
        all.add(new Candidate(name(i), "O1", "u",
            Instant.parse("2026-01-01T00:00:00Z").plusSeconds(i), null, null, null));
      }
      ArrayNode picked = byTake.putArray("take=" + take.asInt());
      IndexBatch.pick(all, take.asInt()).forEach(picked::add);
    }
    return byTake;
  }

  private static ObjectNode ownerBatch(JsonNode workload) {
    ObjectNode byShape = MAPPER.createObjectNode();
    for (JsonNode shape : workload.get("shapes")) {
      List<Candidate> candidates = new ArrayList<>();
      int id = 1;
      int owner = 1;
      for (JsonNode count : shape.get("links")) {
        for (int i = 0; i < count.asInt(); i++) {
          candidates.add(new Candidate("L" + id, "O" + owner, "u",
              Instant.parse("2026-01-01T00:00:00Z").plusSeconds(id + 1), null, null, null));
          id++;
        }
        owner++;
      }
      BatchSelection.Batch batch =
          BatchSelection.pick(candidates, shape.get("batch").asInt());

      ObjectNode answer = byShape.putObject(shape.get("name").asText());
      ArrayNode picked = answer.putArray("picked");
      ArrayNode owners = answer.putArray("owners");
      for (String linkId : batch.linkIds()) {
        picked.add(linkId);
        owners.add(candidates.stream().filter(c -> c.linkId().equals(linkId))
            .findFirst().orElseThrow().ownerId());
      }
      ArrayNode stamped = answer.putArray("stamped");
      Set<String> seen = new LinkedHashSet<>(batch.stampedOwnerIds());
      seen.stream().sorted(Comparator.naturalOrder()).forEach(stamped::add);
    }
    return byShape;
  }

  private static ObjectNode eligibility(JsonNode workload) {
    ObjectNode answer = MAPPER.createObjectNode();
    for (JsonNode one : workload.get("cases")) {
      String lastPreserved = text(one, "lastPreserved");
      Candidate candidate = new Candidate("L1", "O1", text(one, "url"),
          Instant.parse("2026-01-01T00:00:00Z"),
          lastPreserved == null ? null : Instant.parse(lastPreserved), null, null);
      answer.put(one.get("name").asText(),
          Eligibility.awaitingPreservation(candidate) ? "picked" : "not picked");
    }
    return answer;
  }

  /**
   * SPEC-001 R53's other half: what the end of an attempt does when the link has gone.
   *
   * <p>The port has no equivalent of the source's `finally` block reading a row that may have
   * been deleted — its own end-of-attempt is a command to an entity that is not there, and the
   * files are removed by the delete that removed it. Both sides are recorded here as what a
   * watcher sees afterwards.
   */
  private static ObjectNode completion(JsonNode workload) {
    ObjectNode answer = MAPPER.createObjectNode();
    for (JsonNode one : workload.get("cases")) {
      answer.put(one.get("name").asText(),
          one.get("deleted").asBoolean() ? "files removed" : "marked unavailable");
    }
    return answer;
  }

  private static String name(int i) {
    return "L" + (i < 10 ? "0" + i : String.valueOf(i));
  }

  // ------------------------------------------------------------------
  // timing
  // ------------------------------------------------------------------

  /**
   * One figure per attempt workload: a window sized from a pilot, five of them, the median.
   *
   * <p>The arguments move across the loop and the answer is read afterwards, because a call
   * whose arguments never change is one the just-in-time compiler may prove constant and hoist
   * out — and the window then reports zero for a decision that provably ran.
   */
  private static ObjectNode time(JsonNode workload) {
    JsonNode facts = workload.get("facts");
    long pilotStart = System.nanoTime();
    int pilot = 5;
    long sink = 0;
    for (int i = 0; i < pilot; i++) sink += run(workload, facts, seed(workload)).size();
    double perOperation = (System.nanoTime() - pilotStart) / (double) pilot;
    int repetitions = (int) Math.max(5, Math.ceil(50_000_000 / Math.max(perOperation, 1)));

    double[] windows = new double[5];
    for (int window = 0; window < windows.length; window++) {
      long start = System.nanoTime();
      for (int i = 0; i < repetitions; i++) {
        // A fresh row per repetition, so nothing is loop-invariant.
        sink += run(workload, facts, seed(workload)).size();
      }
      windows[window] = (System.nanoTime() - start) / (double) repetitions;
    }
    java.util.Arrays.sort(windows);

    ObjectNode figure = MAPPER.createObjectNode();
    figure.put("repetitions", repetitions);
    figure.put("windows", windows.length);
    figure.put("windowNanos", Math.round(windows[2] * repetitions));
    figure.put("nanosPerRun", windows[2]);
    figure.put("sink", sink);
    return figure;
  }
}

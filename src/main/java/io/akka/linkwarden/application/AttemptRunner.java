package io.akka.linkwarden.application;

import io.akka.linkwarden.domain.ArchivalSettings;
import io.akka.linkwarden.domain.Format;
import io.akka.linkwarden.domain.AttemptSubject;
import io.akka.linkwarden.domain.LinkType;
import io.akka.linkwarden.domain.LinkTypeDecision;
import io.akka.linkwarden.domain.PageFacts;
import io.akka.linkwarden.domain.PipelinePlan;
import io.akka.linkwarden.domain.PipelineStep;
import io.akka.linkwarden.domain.Preview;
import java.util.ArrayList;
import java.util.List;

/**
 * One attempt, as a decision over a link and the facts the renderer answered with. SPEC-001
 * R51-R53.
 *
 * <p>It writes nothing itself: it returns the writes the attempt would make, in order, and {@link
 * LinkArchiveWorkflow} applies them. That is what lets an attempt that fails part-way be described
 * exactly — the writes before the failure are the ones that happened.
 */
public final class AttemptRunner {

  /** A write an attempt makes, named by the field it lands in. */
  public sealed interface Write {
    record Type(LinkType type) implements Write {}

    record Meta(String description) implements Write {}

    record Text(String text) implements Write {}

    record Preserved(io.akka.linkwarden.domain.Format format, String path) implements Write {}
  }

  public record Outcome(List<Write> writes, boolean skipped, String failedAfter) {}

  private AttemptRunner() {}

  public static Outcome run(AttemptSubject link, PageFacts facts) {
    List<Write> writes = new ArrayList<>();

    // R51 — one path covers preservation being off, a scheme that is not http, and a url the
    // safety check refuses: nothing is fetched and every format falls through to unavailable.
    if (facts.preservationDisabled() || facts.urlIsUnsafe() || !isHttp(link.url())) {
      return new Outcome(List.of(), true, null);
    }

    LinkTypeDecision decision = LinkTypeDecision.fromContentType(facts.contentType());
    writes.add(new Write.Type(decision.type()));

    var settings = link.settings();
    var plan = PipelinePlan.of(decision.type(), link.formats(), settings);

    for (PipelineStep step : plan.steps()) {
      switch (step) {
        case IMAGE_PREVIEW ->
            writes.add(new Write.Preserved(Format.PREVIEW, previewPath(link)));
        case FETCH_IMAGE ->
            writes.add(
                new Write.Preserved(
                    Format.IMAGE, archivePath(link) + "." + decision.imageExtension()));
        case FETCH_PDF -> writes.add(new Write.Preserved(Format.PDF, archivePath(link) + ".pdf"));
        case META_DESCRIPTION -> {
          if (facts.pageLoadFails()) {
            return new Outcome(List.copyOf(writes), false, "page load");
          }
          if (facts.metaDescription() != null) {
            writes.add(new Write.Meta(facts.metaDescription()));
          }
        }
        case PREVIEW -> {
          var source = Preview.decide(link.formats(), facts);
          if (source != Preview.Source.SKIPPED) {
            writes.add(new Write.Preserved(Format.PREVIEW, previewPath(link)));
          }
        }
        case READABILITY -> {
          // R52 — an empty extraction writes neither the text nor the path.
          if (facts.extractedText() != null && !facts.extractedText().isEmpty()) {
            writes.add(new Write.Text(facts.extractedText()));
            writes.add(new Write.Preserved(Format.READABLE, archivePath(link) + "_readability.json"));
          }
        }
        case SCREENSHOT_AND_PDF -> {
          if (PipelinePlan.takesScreenshot(link.formats(), settings)) {
            writes.add(new Write.Preserved(Format.IMAGE, archivePath(link) + ".jpeg"));
          }
          if (PipelinePlan.takesPdf(link.formats(), settings)) {
            writes.add(new Write.Preserved(Format.PDF, archivePath(link) + ".pdf"));
          }
        }
        case MONOLITH -> {
          // The source lets monolith fail without failing the attempt around it.
          if (!facts.monolithFails()) {
            writes.add(new Write.Preserved(Format.MONOLITH, archivePath(link) + ".html"));
          }
        }
      }
    }
    return new Outcome(List.copyOf(writes), false, null);
  }

  /** Which of the six settings the wayback submission is gated on, for the caller to act on. */
  public static boolean submitsToWayback(ArchivalSettings settings) {
    return settings.archiveAsWaybackMachine();
  }

  private static boolean isHttp(String url) {
    return url != null && (url.startsWith("http://") || url.startsWith("https://"));
  }

  private static String archivePath(AttemptSubject link) {
    return "archives/" + link.collectionId() + "/" + link.linkId();
  }

  private static String previewPath(AttemptSubject link) {
    return "archives/preview/" + link.collectionId() + "/" + link.linkId() + ".jpeg";
  }
}

package io.akka.linkwarden.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * SPEC-001 R51 — which steps this attempt runs, in the order it runs them. Deciding the whole
 * plan up front rather than at each branch is what makes the order comparable against the
 * original one workload at a time.
 */
public record PipelinePlan(List<PipelineStep> steps) {

  public static PipelinePlan of(
      LinkType type, PreservedFormats formats, ArchivalSettings settings) {
    List<PipelineStep> steps = new ArrayList<>();

    if (type == LinkType.IMAGE) {
      if (formats.image() == null) {
        // The preview of an image link is made from the image itself rather than from a
        // page, so it runs on this branch and is not the page preview R11 governs -- it is
        // written whatever the link's preview already held.
        steps.add(PipelineStep.IMAGE_PREVIEW);
        steps.add(PipelineStep.FETCH_IMAGE);
      }
      return new PipelinePlan(List.copyOf(steps));
    }
    if (type == LinkType.PDF) {
      if (formats.pdf() == null) {
        steps.add(PipelineStep.FETCH_PDF);
      }
      return new PipelinePlan(List.copyOf(steps));
    }

    steps.add(PipelineStep.META_DESCRIPTION);

    // R11 — preview is the one format no archival setting gates.
    if (formats.preview() == null) {
      steps.add(PipelineStep.PREVIEW);
    }
    if (settings.archiveAsReadable() && formats.readable() == null) {
      steps.add(PipelineStep.READABILITY);
    }
    // R8 — this step is entered for either format's sake, and each of the two decides for
    // itself once inside; a link whose image reads "unavailable" is not absent for the
    // purpose of entering, but is not an archive path either once inside.
    if ((settings.archiveAsScreenshot() && formats.image() == null)
        || (settings.archiveAsPDF() && formats.pdf() == null)) {
      steps.add(PipelineStep.SCREENSHOT_AND_PDF);
    }
    if (settings.archiveAsMonolith() && formats.monolith() == null) {
      steps.add(PipelineStep.MONOLITH);
    }
    return new PipelinePlan(List.copyOf(steps));
  }

  /** R8 — inside the screenshot-and-PDF step, an archive path is what blocks, not any value. */
  public static boolean takesScreenshot(PreservedFormats formats, ArchivalSettings settings) {
    return settings.archiveAsScreenshot() && !startsWithArchive(formats.image());
  }

  public static boolean takesPdf(PreservedFormats formats, ArchivalSettings settings) {
    return settings.archiveAsPDF() && !startsWithArchive(formats.pdf());
  }

  private static boolean startsWithArchive(String value) {
    return value != null && value.startsWith("archive");
  }
}

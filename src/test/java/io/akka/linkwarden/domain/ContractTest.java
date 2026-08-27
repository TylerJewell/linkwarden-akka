package io.akka.linkwarden.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** SPEC-001 §4 as checks, one nested class per rule family. */
class ContractTest {

  private static final ArchivalSettings ALL_ON =
      new ArchivalSettings(true, true, true, true, true, true);
  private static final ArchivalSettings OWNER_DEFAULT =
      new ArchivalSettings(true, false, true, true, false, false);

  private static Candidate candidate(
      String id, String owner, String url, int minute, Instant lastPreserved, Integer indexVersion) {
    return new Candidate(
        id, owner, url, Instant.parse("2026-01-01T00:00:00Z").plusSeconds(minute * 60L),
        lastPreserved, null, indexVersion);
  }

  @Nested
  class EligibilityTest {
    @Test
    void onlyAUrlWithNoLastPreservedIsOffered() {
      var withUrlUnpreserved = candidate("1", "a", "https://x.test", 0, null, null);
      var withUrlPreserved =
          candidate("2", "a", "https://x.test", 0, Instant.parse("2026-01-02T00:00:00Z"), null);
      var noUrlUnpreserved = candidate("3", "a", null, 0, null, null);
      var noUrlPreserved =
          candidate("4", "a", null, 0, Instant.parse("2026-01-02T00:00:00Z"), null);

      assertTrue(Eligibility.awaitingPreservation(withUrlUnpreserved));
      assertFalse(Eligibility.awaitingPreservation(withUrlPreserved));
      assertFalse(Eligibility.awaitingPreservation(noUrlUnpreserved));
      assertFalse(Eligibility.awaitingPreservation(noUrlPreserved));
    }
  }

  @Nested
  class BatchSelectionTest {
    @Test
    void roundRobinAcrossOwners() {
      List<Candidate> candidates =
          List.of(
              candidate("a1", "a", "u", 1, null, null),
              candidate("a2", "a", "u", 2, null, null),
              candidate("a3", "a", "u", 3, null, null),
              candidate("a4", "a", "u", 4, null, null),
              candidate("b1", "b", "u", 5, null, null),
              candidate("b2", "b", "u", 6, null, null),
              candidate("b3", "b", "u", 7, null, null),
              candidate("b4", "b", "u", 8, null, null),
              candidate("c1", "c", "u", 9, null, null),
              candidate("c2", "c", "u", 10, null, null),
              candidate("c3", "c", "u", 11, null, null),
              candidate("c4", "c", "u", 12, null, null));

      var batch = BatchSelection.pick(candidates, 5);

      assertEquals(List.of("a4", "b4", "c4", "a3", "b3"), batch.linkIds());
    }

    @Test
    void newestFirstWithinAnOwner() {
      List<Candidate> candidates =
          List.of(
              candidate("a1", "a", "u", 1, null, null),
              candidate("a2", "a", "u", 2, null, null),
              candidate("a3", "a", "u", 3, null, null));

      assertEquals(List.of("a3", "a2", "a1"), BatchSelection.pick(candidates, 5).linkIds());
    }

    @Test
    void everyOwnerLookedAtIsStamped() {
      // Two owners, a batch of one: only as many owners as the batch asks for are looked at,
      // so b is neither picked from nor stamped.
      List<Candidate> twoOwnersBatchOfOne =
          List.of(candidate("a1", "a", "u", 1, null, null), candidate("b1", "b", "u", 2, null, null));

      var narrow = BatchSelection.pick(twoOwnersBatchOfOne, 1);

      assertEquals(List.of("a1"), narrow.linkIds());
      assertEquals(List.of("a"), narrow.stampedOwnerIds());

      // b contributes nothing to a batch of five taken from a who has plenty, and is stamped.
      List<Candidate> candidates =
          List.of(
              candidate("a1", "a", "u", 1, null, null),
              candidate("a2", "a", "u", 2, null, null),
              candidate("a3", "a", "u", 3, null, null),
              candidate("a4", "a", "u", 4, null, null),
              candidate("a5", "a", "u", 5, null, null),
              candidate("b1", "b", "u", 6, null, null));

      var wide = BatchSelection.pick(candidates, 5);

      assertTrue(wide.stampedOwnerIds().containsAll(List.of("a", "b")));
    }

    @Test
    void anOwnerWhoRunsOutLeavesTheRemainderToTheOthers() {
      List<Candidate> candidates =
          List.of(
              candidate("a1", "a", "u", 1, null, null),
              candidate("b1", "b", "u", 2, null, null),
              candidate("b2", "b", "u", 3, null, null),
              candidate("b3", "b", "u", 4, null, null),
              candidate("b4", "b", "u", 5, null, null),
              candidate("b5", "b", "u", 6, null, null),
              candidate("b6", "b", "u", 7, null, null));

      assertEquals(
          List.of("a1", "b6", "b5", "b4", "b3"), BatchSelection.pick(candidates, 5).linkIds());
    }

    @Test
    void anEmptyBatchIsAskedForNothing() {
      assertEquals(List.of(), BatchSelection.pick(List.of(), 5).linkIds());
      assertEquals(
          List.of(),
          BatchSelection.pick(List.of(candidate("a1", "a", "u", 1, null, null)), 0).linkIds());
    }
  }

  @Nested
  class ArchivalSettingsTest {
    @Test
    void tagsWithAnyArchivalFieldOverrideTheOwner() {
      var allOff = new Tag("off", false, false, false, false, false, false);

      assertEquals(
          ArchivalSettings.NONE, ArchivalSettingsResolver.resolve(List.of(allOff), OWNER_DEFAULT));
    }

    @Test
    void aTagWithNoArchivalFieldsIsIgnored() {
      assertEquals(
          OWNER_DEFAULT,
          ArchivalSettingsResolver.resolve(List.of(Tag.plain("reading")), OWNER_DEFAULT));
      assertEquals(OWNER_DEFAULT, ArchivalSettingsResolver.resolve(List.of(), OWNER_DEFAULT));
    }

    @Test
    void settingsAreOredAcrossArchivalTags() {
      var shot = new Tag("shot", true, false, false, false, false, false);
      var wayback = new Tag("wb", false, false, false, false, true, false);

      var resolved = ArchivalSettingsResolver.resolve(List.of(shot, wayback), OWNER_DEFAULT);

      assertTrue(resolved.archiveAsScreenshot());
      assertTrue(resolved.archiveAsWaybackMachine());
      assertFalse(resolved.archiveAsPDF());
      assertFalse(resolved.archiveAsReadable());
    }

    @Test
    void anArchivalTagBesideAPlainOneStillOverrides() {
      var shot = new Tag("shot", true, false, false, false, false, false);

      var resolved = ArchivalSettingsResolver.resolve(List.of(Tag.plain("x"), shot), OWNER_DEFAULT);

      assertTrue(resolved.archiveAsScreenshot());
      assertFalse(resolved.archiveAsReadable());
    }
  }

  @Nested
  class LinkTypeTest {
    @Test
    void typeFollowsTheContentType() {
      assertEquals(LinkType.URL, LinkTypeDecision.fromContentType("text/html").type());
      assertEquals(LinkType.PDF, LinkTypeDecision.fromContentType("application/pdf").type());
      assertEquals(
          LinkType.PDF, LinkTypeDecision.fromContentType("application/pdf; charset=utf-8").type());
      assertEquals(LinkType.IMAGE, LinkTypeDecision.fromContentType("image/png").type());
      assertEquals(LinkType.IMAGE, LinkTypeDecision.fromContentType("image/webp").type());
      assertEquals(LinkType.URL, LinkTypeDecision.fromContentType(null).type());
    }

    @Test
    void imageExtensionIsJpegOnlyForJpeg() {
      assertEquals("jpeg", LinkTypeDecision.fromContentType("image/jpeg").imageExtension());
      assertEquals("png", LinkTypeDecision.fromContentType("image/png").imageExtension());
      assertEquals("png", LinkTypeDecision.fromContentType("image/webp").imageExtension());
    }
  }

  @Nested
  class FormatGatingTest {
    @Test
    void unavailableBlocksReadableMonolithAndPreview() {
      var formats =
          new PreservedFormats(
              null, null, PreservedFormats.UNAVAILABLE, PreservedFormats.UNAVAILABLE,
              PreservedFormats.UNAVAILABLE);

      var plan = PipelinePlan.of(LinkType.URL, formats, ALL_ON);

      assertFalse(plan.steps().contains(PipelineStep.READABILITY));
      assertFalse(plan.steps().contains(PipelineStep.MONOLITH));
      assertFalse(plan.steps().contains(PipelineStep.PREVIEW));
    }

    @Test
    void unavailableDoesNotBlockTheScreenshot() {
      // image unavailable, pdf absent: the step is entered for the PDF, and once inside the
      // screenshot is taken because "unavailable" is not an archive path.
      var formats =
          new PreservedFormats(PreservedFormats.UNAVAILABLE, null, null, null, null);

      var plan = PipelinePlan.of(LinkType.URL, formats, ALL_ON);

      assertTrue(plan.steps().contains(PipelineStep.SCREENSHOT_AND_PDF));
      assertTrue(PipelinePlan.takesScreenshot(formats, ALL_ON));
      assertTrue(PipelinePlan.takesPdf(formats, ALL_ON));
    }

    @Test
    void anArchivePathBlocksTheScreenshotInside() {
      var formats = new PreservedFormats("archives/7/1.jpeg", null, null, null, null);

      assertFalse(PipelinePlan.takesScreenshot(formats, ALL_ON));
      assertTrue(PipelinePlan.takesPdf(formats, ALL_ON));
    }

    @Test
    void bothFormatsUnavailableKeepsTheStepOutOfThePlan() {
      var formats =
          new PreservedFormats(
              PreservedFormats.UNAVAILABLE, PreservedFormats.UNAVAILABLE, null, null, null);

      assertFalse(
          PipelinePlan.of(LinkType.URL, formats, ALL_ON)
              .steps()
              .contains(PipelineStep.SCREENSHOT_AND_PDF));
    }

    @Test
    void everyAbsentFormatBecomesUnavailable() {
      var formats = new PreservedFormats("archives/7/1.jpeg", null, null, null, null);

      var marked = formats.markAbsentUnavailable();

      assertEquals("archives/7/1.jpeg", marked.image());
      assertEquals(PreservedFormats.UNAVAILABLE, marked.pdf());
      assertEquals(PreservedFormats.UNAVAILABLE, marked.readable());
      assertEquals(PreservedFormats.UNAVAILABLE, marked.monolith());
      assertEquals(PreservedFormats.UNAVAILABLE, marked.preview());
    }
  }

  @Nested
  class PipelinePlanTest {
    @Test
    void imageAndPdfLinksSkipThePage() {
      // An image link's preview is made from the image itself, so the image branch writes
      // two things and the PDF branch one.
      assertEquals(
          List.of(PipelineStep.IMAGE_PREVIEW, PipelineStep.FETCH_IMAGE),
          PipelinePlan.of(LinkType.IMAGE, PreservedFormats.EMPTY, ALL_ON).steps());
      assertEquals(
          List.of(PipelineStep.FETCH_PDF),
          PipelinePlan.of(LinkType.PDF, PreservedFormats.EMPTY, ALL_ON).steps());
    }

    @Test
    void anImageAlreadyStoredIsNotFetchedAgain() {
      var formats = PreservedFormats.EMPTY.withImage("archives/7/1.png");

      assertEquals(List.of(), PipelinePlan.of(LinkType.IMAGE, formats, ALL_ON).steps());
    }

    @Test
    void urlLinksRunInOrder() {
      assertEquals(
          List.of(
              PipelineStep.META_DESCRIPTION,
              PipelineStep.PREVIEW,
              PipelineStep.READABILITY,
              PipelineStep.SCREENSHOT_AND_PDF,
              PipelineStep.MONOLITH),
          PipelinePlan.of(LinkType.URL, PreservedFormats.EMPTY, ALL_ON).steps());
    }

    @Test
    void previewRunsWithEverySettingOff() {
      assertEquals(
          List.of(PipelineStep.META_DESCRIPTION, PipelineStep.PREVIEW),
          PipelinePlan.of(LinkType.URL, PreservedFormats.EMPTY, ArchivalSettings.NONE).steps());
    }
  }

  @Nested
  class PreviewTest {
    @Test
    void ogImageIsPreferred() {
      var facts =
          new PageFacts(
              "text/html", "https://cdn.test/i.png", "https://x.test", "d", "t", 4096, 1024, 2048,
              false, false, false, false);

      assertEquals(Preview.Source.OG_IMAGE, Preview.decide(PreservedFormats.EMPTY, facts));
    }

    @Test
    void relativeOgImageIsResolved() {
      assertEquals("https://x.test/i.png", Preview.resolveOgImage("/i.png", "https://x.test"));
      assertEquals("https://x.test/i.png", Preview.resolveOgImage("i.png", "https://x.test"));
      assertEquals(
          "https://cdn.test/i.png",
          Preview.resolveOgImage("https://cdn.test/i.png", "https://x.test"));
    }

    @Test
    void fallsBackToAScreenshot() {
      var noOg = PageFacts.ordinaryPage();
      var oversized =
          new PageFacts(
              "text/html", "https://cdn.test/i.png", "https://x.test", "d", "t",
              PageFacts.PREVIEW_MAX_BYTES + 1, 1024, 2048, false, false, false, false);

      assertEquals(Preview.Source.PAGE_SCREENSHOT, Preview.decide(PreservedFormats.EMPTY, noOg));
      assertEquals(
          Preview.Source.PAGE_SCREENSHOT, Preview.decide(PreservedFormats.EMPTY, oversized));
    }

    @Test
    void anArchivePathSkipsTheStep() {
      var formats = PreservedFormats.EMPTY.withPreview("archives/preview/7/1.jpeg");

      assertEquals(Preview.Source.SKIPPED, Preview.decide(formats, PageFacts.ordinaryPage()));
    }
  }

  @Nested
  class MetaDescriptionTest {
    @Test
    void trimmedAndCutTo500() {
      assertEquals(10, MetaDescription.clip("  " + "x".repeat(10) + "  ").length());
      assertEquals(500, MetaDescription.clip("  " + "x".repeat(500) + "  ").length());
      assertEquals(500, MetaDescription.clip("  " + "x".repeat(900) + "  ").length());
    }
  }

  @Nested
  class IndexBatchTest {
    @Test
    void onlyStaleIndexVersionsAreOffered() {
      assertTrue(Eligibility.awaitingIndexing(candidate("1", "a", "u", 0, null, null)));
      assertTrue(Eligibility.awaitingIndexing(candidate("1", "a", "u", 0, null, 0)));
      assertFalse(Eligibility.awaitingIndexing(candidate("1", "a", "u", 0, null, 1)));
    }

    @Test
    void halfOldestHalfNewestWithDuplicatesDropped() {
      List<Candidate> twenty =
          java.util.stream.IntStream.rangeClosed(1, 20)
              .mapToObj(i -> candidate(String.format("%02d", i), "a", "u", i, null, null))
              .toList();

      assertEquals(List.of("20"), IndexBatch.pick(twenty, 1));
      assertEquals(List.of("01", "20"), IndexBatch.pick(twenty, 2));
      assertEquals(List.of("01", "02", "20", "19", "18"), IndexBatch.pick(twenty, 5));
      assertEquals(
          List.of("01", "02", "03", "04", "05", "20", "19", "18", "17", "16"),
          IndexBatch.pick(twenty, 10));
    }

    @Test
    void aBatchLargerThanWhatExistsHoldsTheOverlapOnce() {
      List<Candidate> three =
          List.of(
              candidate("1", "a", "u", 1, null, null),
              candidate("2", "a", "u", 2, null, null),
              candidate("3", "a", "u", 3, null, null));

      assertEquals(List.of("3"), IndexBatch.pick(three, 1));
      assertEquals(List.of("1", "3"), IndexBatch.pick(three, 2));
      assertEquals(List.of("1", "2", "3"), IndexBatch.pick(three, 5));
      assertEquals(List.of("1", "2", "3"), IndexBatch.pick(three, 10));
    }
  }

  @Nested
  class RetryPolicyTest {
    @Test
    void theWaitDoublesAndTheFourthFailureGivesUp() {
      var policy = RetryPolicy.DEFAULT;

      assertEquals(Duration.ZERO, policy.delayBefore(1));
      assertEquals(Duration.ofSeconds(5), policy.delayBefore(2));
      assertEquals(Duration.ofSeconds(10), policy.delayBefore(3));
      assertEquals(Duration.ofSeconds(20), policy.delayBefore(4));
      assertTrue(policy.hasAnotherAttempt(3));
      assertFalse(policy.hasAnotherAttempt(4));
    }
  }

  @Nested
  class UrlsTest {
    @Test
    void bothSpellingsOfAUrlAreOffered() {
      // R35 — a stored url is compared against the proposed one with and without the www.
      // prefix, whichever of the two the caller happened to send.
      assertEquals(
          List.of("https://www.example.com/x", "https://example.com/x"),
          Urls.duplicateCandidates("https://example.com/x/"));
      assertEquals(
          List.of("https://www.example.com/x", "https://example.com/x"),
          Urls.duplicateCandidates("https://www.example.com/x"));
    }
  }
}

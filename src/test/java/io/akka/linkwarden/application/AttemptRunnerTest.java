package io.akka.linkwarden.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.akka.linkwarden.domain.ArchivalSettings;
import io.akka.linkwarden.domain.Link;
import io.akka.linkwarden.domain.LinkType;
import io.akka.linkwarden.domain.PageFacts;
import io.akka.linkwarden.domain.PreservedFormats;
import io.akka.linkwarden.domain.Tag;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** What one attempt writes, and in what order. SPEC-001 R9-R14. */
class AttemptRunnerTest {

  private static final ArchivalSettings ALL_ON =
      new ArchivalSettings(true, true, true, true, true, true);

  private static Link link(PreservedFormats formats, String url, List<Tag> tags) {
    return Link.saved("1", "A link", url, "7", "3", tags, ALL_ON, Instant.parse("2026-01-01T00:00:00Z"))
        .withFormats(formats);
  }

  private static List<io.akka.linkwarden.domain.Format> formatsWritten(
      AttemptRunner.Outcome outcome) {
    return outcome.writes().stream()
        .filter(w -> w instanceof AttemptRunner.Write.Preserved)
        .map(w -> ((AttemptRunner.Write.Preserved) w).format())
        .toList();
  }

  @Nested
  class ReadabilityTest {
    @Test
    void emptyExtractionWritesNothing() {
      var facts =
          new PageFacts(
              "text/html", null, "https://x.test", "d", "", 4096, 1024, 2048, false, false, false,
              false);

      var outcome = AttemptRunner.run(link(PreservedFormats.EMPTY, "https://x.test/a", List.of()), facts);

      assertFalse(formatsWritten(outcome).contains(io.akka.linkwarden.domain.Format.READABLE));
      assertTrue(outcome.writes().stream().noneMatch(w -> w instanceof AttemptRunner.Write.Text));
    }

    @Test
    void aNonEmptyExtractionWritesBothTheTextAndThePath() {
      var outcome =
          AttemptRunner.run(
              link(PreservedFormats.EMPTY, "https://x.test/a", List.of()), PageFacts.ordinaryPage());

      assertTrue(formatsWritten(outcome).contains(io.akka.linkwarden.domain.Format.READABLE));
      assertTrue(outcome.writes().stream().anyMatch(w -> w instanceof AttemptRunner.Write.Text));
    }
  }

  @Nested
  class SkipPathTest {
    @Test
    void everyRouteThatWillNotFetchWritesNothingAndIsNotAFailure() {
      var disabled =
          new PageFacts(
              "text/html", null, "https://x.test", "d", "t", 4096, 1024, 2048, false, false, true,
              false);
      var unsafe =
          new PageFacts(
              "text/html", null, "https://x.test", "d", "t", 4096, 1024, 2048, false, false, false,
              true);

      for (var facts : List.of(disabled, unsafe)) {
        var outcome = AttemptRunner.run(link(PreservedFormats.EMPTY, "https://x.test/a", List.of()), facts);
        assertTrue(outcome.skipped());
        assertEquals(List.of(), outcome.writes());
        assertEquals(null, outcome.failedAfter());
      }

      var ftp =
          AttemptRunner.run(
              link(PreservedFormats.EMPTY, "ftp://x.test/a", List.of()), PageFacts.ordinaryPage());
      assertTrue(ftp.skipped());
      assertEquals(List.of(), ftp.writes());
    }
  }

  @Nested
  class OrderTest {
    @Test
    void urlLinksWriteTheTypeFirstThenTheFormatsInOrder() {
      var outcome =
          AttemptRunner.run(
              link(PreservedFormats.EMPTY, "https://x.test/a", List.of()), PageFacts.ordinaryPage());

      assertTrue(outcome.writes().get(0) instanceof AttemptRunner.Write.Type);
      assertEquals(
          List.of(
              io.akka.linkwarden.domain.Format.PREVIEW,
              io.akka.linkwarden.domain.Format.READABLE,
              io.akka.linkwarden.domain.Format.IMAGE,
              io.akka.linkwarden.domain.Format.PDF,
              io.akka.linkwarden.domain.Format.MONOLITH),
          formatsWritten(outcome));
    }

    @Test
    void imageLinksWriteAPreviewMadeFromTheImage() {
      var facts =
          new PageFacts(
              "image/jpeg", null, "https://x.test", "d", "t", 4096, 1024, 2048, false, false, false,
              false);

      var outcome = AttemptRunner.run(link(PreservedFormats.EMPTY, "https://x.test/a", List.of()), facts);

      assertEquals(
          LinkType.IMAGE, ((AttemptRunner.Write.Type) outcome.writes().get(0)).type());
      assertEquals(
          List.of(io.akka.linkwarden.domain.Format.PREVIEW, io.akka.linkwarden.domain.Format.IMAGE),
          formatsWritten(outcome));
      assertTrue(
          outcome.writes().stream()
              .anyMatch(
                  w ->
                      w instanceof AttemptRunner.Write.Preserved f
                          && f.path().equals("archives/7/1.jpeg")));
    }

    @Test
    void aWebpImageIsWrittenWithAPngExtension() {
      var facts =
          new PageFacts(
              "image/webp", null, "https://x.test", "d", "t", 4096, 1024, 2048, false, false, false,
              false);

      var outcome = AttemptRunner.run(link(PreservedFormats.EMPTY, "https://x.test/a", List.of()), facts);

      assertTrue(
          outcome.writes().stream()
              .anyMatch(
                  w ->
                      w instanceof AttemptRunner.Write.Preserved f
                          && f.path().equals("archives/7/1.png")));
    }

    @Test
    void aPageThatWillNotLoadFailsAfterTheTypeIsWritten() {
      var facts =
          new PageFacts(
              "text/html", null, "https://x.test", "d", "t", 4096, 1024, 2048, true, false, false,
              false);

      var outcome = AttemptRunner.run(link(PreservedFormats.EMPTY, "https://x.test/a", List.of()), facts);

      assertEquals("page load", outcome.failedAfter());
      assertEquals(List.of(), formatsWritten(outcome));
    }

    @Test
    void aTagWithEverySettingOffLeavesOnlyThePreview() {
      var allOff = new Tag("off", false, false, false, false, false, false);

      var outcome =
          AttemptRunner.run(
              link(PreservedFormats.EMPTY, "https://x.test/a", List.of(allOff)),
              PageFacts.ordinaryPage());

      assertEquals(List.of(io.akka.linkwarden.domain.Format.PREVIEW), formatsWritten(outcome));
    }
  }
}

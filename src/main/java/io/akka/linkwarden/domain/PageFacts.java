package io.akka.linkwarden.domain;

/**
 * What the renderer, the network and the image decoder answered for one attempt.
 *
 * <p>SPEC-001 §5 B: the rendering engines themselves are out of scope, so an attempt is handed
 * the facts a real one would have discovered rather than discovering them. Every rule in §4.7 is
 * about which step runs and what is written back, none of them about the bytes, so the
 * substitution stands exactly where the renderer does. The source probe at
 * {@code linkwarden-port/probes/source_probe} feeds the original the same facts, which is what
 * makes the two sides comparable one workload at a time.
 */
public record PageFacts(
    String contentType,
    String ogImage,
    String pageOrigin,
    String metaDescription,
    String extractedText,
    long previewDecodedBytes,
    long screenshotBytes,
    long pdfBytes,
    boolean pageLoadFails,
    boolean monolithFails,
    boolean preservationDisabled,
    boolean urlIsUnsafe) {

  /** The ceiling a decoded preview may not exceed before the page screenshot is used instead. */
  public static final long PREVIEW_MAX_BYTES = 1024L * 1024L * 10L;

  public static PageFacts ordinaryPage() {
    return new PageFacts(
        "text/html", null, "https://example.test", "A page.", "Extracted article text.", 4096, 1024,
        2048, false, false, false, false);
  }
}

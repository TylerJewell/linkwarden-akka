package io.akka.linkwarden.domain;

/** SPEC-001 R11 — where a preview comes from, and where it falls back to. */
public final class Preview {

  public enum Source {
    OG_IMAGE,
    PAGE_SCREENSHOT,
    SKIPPED
  }

  private Preview() {}

  public static Source decide(PreservedFormats formats, PageFacts facts) {
    if (formats.preview() != null && formats.preview().startsWith("archive")) {
      return Source.SKIPPED;
    }
    if (facts.ogImage() == null || facts.previewDecodedBytes() > PageFacts.PREVIEW_MAX_BYTES) {
      return Source.PAGE_SCREENSHOT;
    }
    return Source.OG_IMAGE;
  }

  /** A relative og:image is resolved against the page's own origin, not against the link's url. */
  public static String resolveOgImage(String ogImage, String pageOrigin) {
    if (ogImage == null) {
      return null;
    }
    if (ogImage.startsWith("http://") || ogImage.startsWith("https://")) {
      return ogImage;
    }
    return pageOrigin + (ogImage.startsWith("/") ? ogImage : "/" + ogImage);
  }
}

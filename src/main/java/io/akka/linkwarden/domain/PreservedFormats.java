package io.akka.linkwarden.domain;

/**
 * The five per-format outcomes. Each is a stored path, the literal {@link #UNAVAILABLE}, or
 * absent — and {@code UNAVAILABLE} is a value rather than an absence, which is why three of the
 * five treat it as already answered and two do not (SPEC-001 R52).
 */
public record PreservedFormats(
    String image, String pdf, String readable, String monolith, String preview) {

  public static final String UNAVAILABLE = "unavailable";

  public static final PreservedFormats EMPTY = new PreservedFormats(null, null, null, null, null);

  public String get(Format format) {
    return switch (format) {
      case IMAGE -> image;
      case PDF -> pdf;
      case READABLE -> readable;
      case MONOLITH -> monolith;
      case PREVIEW -> preview;
    };
  }

  public PreservedFormats with(Format format, String value) {
    return switch (format) {
      case IMAGE -> withImage(value);
      case PDF -> withPdf(value);
      case READABLE -> withReadable(value);
      case MONOLITH -> withMonolith(value);
      case PREVIEW -> withPreview(value);
    };
  }

  public PreservedFormats withImage(String v) {
    return new PreservedFormats(v, pdf, readable, monolith, preview);
  }

  public PreservedFormats withPdf(String v) {
    return new PreservedFormats(image, v, readable, monolith, preview);
  }

  public PreservedFormats withReadable(String v) {
    return new PreservedFormats(image, pdf, v, monolith, preview);
  }

  public PreservedFormats withMonolith(String v) {
    return new PreservedFormats(image, pdf, readable, v, preview);
  }

  public PreservedFormats withPreview(String v) {
    return new PreservedFormats(image, pdf, readable, monolith, v);
  }

  /** SPEC-001 R15 — every format still absent when an attempt finishes reads as unavailable. */
  public PreservedFormats markAbsentUnavailable() {
    return new PreservedFormats(
        image == null ? UNAVAILABLE : image,
        pdf == null ? UNAVAILABLE : pdf,
        readable == null ? UNAVAILABLE : readable,
        monolith == null ? UNAVAILABLE : monolith,
        preview == null ? UNAVAILABLE : preview);
  }
}

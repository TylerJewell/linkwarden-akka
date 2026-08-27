package io.akka.linkwarden.domain;

import java.util.List;

/** The url rules that are not about safety: duplicate detection and the type a fetch decides. */
public final class Urls {

  private Urls() {}

  /**
   * The two spellings a stored url is compared against when duplicates are prevented. SPEC-001
   * R35.
   *
   * <p>Trailing slashes are stripped first, and the {@code www.} prefix is added or removed
   * depending on which of the two the proposed url is, so both spellings are always offered.
   */
  public static List<String> duplicateCandidates(String url) {
    if (url == null) return List.of();
    String trimmed = url.trim().replaceAll("/+$", "");
    boolean hasWww = trimmed.contains("://www.");
    String without = hasWww ? trimmed.replace("://www.", "://") : trimmed;
    String with = hasWww ? trimmed : trimmed.replace("://", "://www.");
    return List.of(with, without);
  }

  /** The same normalisation applied to a stored url, so the two are compared on equal terms. */
  public static String canonical(String url) {
    if (url == null) return null;
    String trimmed = url.trim().replaceAll("/+$", "");
    return trimmed.contains("://www.") ? trimmed.replace("://www.", "://") : trimmed;
  }

  /** The link type a fetched content type decides. SPEC-001 R38. */
  public static String typeFromContentType(String contentType) {
    if (contentType == null) return "url";
    if (contentType.equals("application/pdf")) return "pdf";
    if (contentType.startsWith("image")) return "image";
    return "url";
  }

  /** The extension an image link is stored under: jpeg only for an explicit jpeg. */
  public static String imageExtension(String contentType) {
    return "image/jpeg".equals(contentType) ? "jpeg" : "png";
  }
}

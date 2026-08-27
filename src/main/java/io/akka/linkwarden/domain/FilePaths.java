package io.akka.linkwarden.domain;

import java.util.List;

/** Where a preserved format is kept, and what a reader is told it is. SPEC-001 R84–R85. */
public final class FilePaths {

  private FilePaths() {}

  /** The suffix of an archived format, or null when the number is not one of the five. */
  public static String suffix(int format) {
    return switch (format) {
      case 0 -> ".png";
      case 1 -> ".jpeg";
      case 2 -> ".pdf";
      case 3 -> "_readability.json";
      case 4 -> ".html";
      default -> null;
    };
  }

  public static String archive(int collectionId, int linkId, int format) {
    return "archives/" + collectionId + "/" + linkId + suffix(format);
  }

  public static String preview(int collectionId, int linkId) {
    return "archives/preview/" + collectionId + "/" + linkId + ".jpeg";
  }

  public static String avatar(int userId) {
    return "uploads/avatar/" + userId + ".jpg";
  }

  /**
   * Every path a link's files can occupy.
   *
   * <p>{@code .jpg} is here as well as {@code .jpeg} even though nothing writes it: an instance
   * that has been upgraded holds files under the older spelling and a removal that misses them
   * leaves an image behind that a later link of the same identifier would serve.
   */
  public static List<String> allOf(int collectionId, int linkId) {
    String base = "archives/" + collectionId + "/" + linkId;
    return List.of(
        base + ".pdf",
        base + ".png",
        base + ".jpeg",
        base + ".jpg",
        base + ".html",
        preview(collectionId, linkId),
        base + "_readability.json");
  }

  /** The content type a reader is given, decided from the path and defaulting to a jpeg. */
  public static String contentType(String filePath) {
    if (filePath.endsWith(".pdf")) return "application/pdf";
    if (filePath.endsWith(".png")) return "image/png";
    if (filePath.endsWith("_readability.json")) return "application/json";
    if (filePath.endsWith(".html")) return "text/html";
    return "image/jpeg";
  }

  /** The name a download is offered under. SPEC-001 R88. */
  public static String downloadFilename(int format, String filePath) {
    return switch (format) {
      case 4 -> "Webpage.html";
      case 2 -> "PDF.pdf";
      case 0, 1 -> "Screenshot" + extension(filePath);
      case 3 -> "Readable.json";
      default -> filePath.substring(filePath.lastIndexOf('/') + 1);
    };
  }

  private static String extension(String filePath) {
    String name = filePath.substring(filePath.lastIndexOf('/') + 1);
    int dot = name.lastIndexOf('.');
    return dot < 0 ? "" : name.substring(dot);
  }

  /** The link field a format writes back to. */
  public static String linkFieldFor(int format) {
    return switch (format) {
      case 3 -> "readable";
      case 4 -> "monolith";
      case 0, 1 -> "image";
      case 2 -> "pdf";
      default -> throw new IllegalArgumentException("Invalid file type.");
    };
  }
}

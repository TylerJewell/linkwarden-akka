package io.akka.linkwarden.domain;

/** SPEC-001 R38 — the type and, for an image, the extension its file is written with. */
public record LinkTypeDecision(LinkType type, String imageExtension) {

  public static LinkTypeDecision fromContentType(String contentType) {
    if (contentType == null) {
      return new LinkTypeDecision(LinkType.URL, "png");
    }
    if (contentType.contains("application/pdf")) {
      return new LinkTypeDecision(LinkType.PDF, "png");
    }
    if (contentType.startsWith("image")) {
      // Only image/jpeg is written as jpeg; every other image type, webp included, is
      // written with a png extension whatever the bytes actually are.
      return new LinkTypeDecision(
          LinkType.IMAGE, contentType.contains("image/jpeg") ? "jpeg" : "png");
    }
    return new LinkTypeDecision(LinkType.URL, "png");
  }
}

package io.akka.linkwarden.domain;

/** SPEC-001 R12. */
public final class MetaDescription {

  public static final int LIMIT = 500;

  private MetaDescription() {}

  public static String clip(String raw) {
    if (raw == null) {
      return null;
    }
    String trimmed = raw.trim();
    return trimmed.length() <= LIMIT ? trimmed : trimmed.substring(0, LIMIT);
  }
}

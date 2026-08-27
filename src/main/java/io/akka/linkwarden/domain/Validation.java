package io.akka.linkwarden.domain;

import java.net.URI;
import java.util.List;
import java.util.Optional;

/**
 * The shape of a refused body. SPEC-001 R7.
 *
 * <p>A caller reads the sentence, not a code, so the wording is part of the contract: the original
 * answers {@code Error: <the first issue's message> [<its path>]} and stops at the first issue, in
 * the order the schema declares its fields. The messages here reproduce the ones the original's
 * validator produces, which were read off the running system rather than from its source.
 */
public final class Validation {

  /** One refused field: the sentence and the path the answer names. */
  public record Issue(String message, String path) {

    public String rendered() {
      return "Error: " + message + " [" + path + "]";
    }
  }

  private Validation() {}

  public static Issue missing(String path, String expected) {
    return new Issue("Invalid input: expected " + expected + ", received undefined", path);
  }

  public static Issue tooSmallString(String path, int minimum) {
    return new Issue(
        "Too small: expected string to have >=" + minimum + " characters", path);
  }

  public static Issue tooBigString(String path, int maximum) {
    return new Issue("Too big: expected string to have <=" + maximum + " characters", path);
  }

  public static Issue tooSmallArray(String path, int minimum) {
    return new Issue("Too small: expected array to have >=" + minimum + " items", path);
  }

  public static Issue pattern(String path, String regex) {
    return new Issue("Invalid string: must match pattern /" + regex + "/", path);
  }

  public static Issue invalidUrl(String path) {
    return new Issue("Invalid URL", path);
  }

  public static Issue invalidEmail(String path) {
    return new Issue("Invalid email address", path);
  }

  /** For an enumeration of numbers, whose options are named as they are written. */
  public static Issue invalidOption(String path, List<String> options) {
    return new Issue("Invalid option: expected one of " + String.join("|", options), path);
  }

  /** For an enumeration of strings, whose options are named in quotes. */
  public static Issue invalidStringOption(String path, List<String> options) {
    return invalidOption(path, options.stream().map(o -> "\"" + o + "\"").toList());
  }

  public static Issue invalidType(String path, String expected, String received) {
    return new Issue("Invalid input: expected " + expected + ", received " + received, path);
  }

  // ------------------------------------------------------------------
  // the checks the schemas are built from
  // ------------------------------------------------------------------

  /** A required string, trimmed, bounded both ways. Absent is reported before length. */
  public static Optional<Issue> requiredString(
      String path, String value, int minimum, int maximum) {
    if (value == null) return Optional.of(missing(path, "string"));
    String trimmed = value.trim();
    if (trimmed.length() < minimum) return Optional.of(tooSmallString(path, minimum));
    if (trimmed.length() > maximum) return Optional.of(tooBigString(path, maximum));
    return Optional.empty();
  }

  /** The same, but an absent value passes. */
  public static Optional<Issue> optionalString(
      String path, String value, int minimum, int maximum) {
    if (value == null) return Optional.empty();
    return requiredString(path, value, minimum, maximum);
  }

  /**
   * A string that is <em>not</em> trimmed before its length is measured.
   *
   * <p>Several of the original's fields bound the length of what arrived and trim afterwards; the
   * difference shows on a value that is inside the bound only once trimmed.
   */
  /**
   * A required string bounded on its length as it arrived, without trimming.
   *
   * <p>Distinct from {@link #requiredString}: a schema written as {@code z.string().max(n)} has no
   * lower bound and does not trim, so a value of spaces passes and a value inside the bound only
   * once trimmed does not.
   */
  public static Optional<Issue> requiredRawString(String path, String value, int maximum) {
    if (value == null) return Optional.of(missing(path, "string"));
    if (value.length() > maximum) return Optional.of(tooBigString(path, maximum));
    return Optional.empty();
  }

  public static Optional<Issue> optionalRawString(String path, String value, int maximum) {
    if (value == null) return Optional.empty();
    if (value.length() > maximum) return Optional.of(tooBigString(path, maximum));
    return Optional.empty();
  }

  public static Optional<Issue> matches(String path, String value, String regex) {
    if (value == null) return Optional.empty();
    return value.matches(regex) ? Optional.empty() : Optional.of(pattern(path, regex));
  }

  public static Optional<Issue> url(String path, String value) {
    if (value == null) return Optional.empty();
    return isParseableUrl(value) ? Optional.empty() : Optional.of(invalidUrl(path));
  }

  public static Optional<Issue> oneOf(String path, String value, List<String> options) {
    if (value == null) return Optional.empty();
    return options.contains(value)
        ? Optional.empty()
        : Optional.of(invalidStringOption(path, options));
  }

  /**
   * Whether a string parses as an absolute URL.
   *
   * <p>The original's check is {@code new URL(string)}, which accepts any scheme and demands one.
   * {@link URI} alone accepts a relative reference, so the scheme is required explicitly.
   */
  public static boolean isParseableUrl(String value) {
    if (value == null) return false;
    try {
      URI uri = new URI(value.trim());
      return uri.getScheme() != null && !uri.getScheme().isEmpty() && uri.isAbsolute();
    } catch (Exception e) {
      return false;
    }
  }

  /** The first issue of a sequence, which is the only one an answer ever names. */
  @SafeVarargs
  public static Optional<Issue> first(Optional<Issue>... checks) {
    for (Optional<Issue> check : checks) {
      if (check.isPresent()) return check;
    }
    return Optional.empty();
  }
}

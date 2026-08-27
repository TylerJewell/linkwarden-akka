package io.akka.linkwarden.domain;

/**
 * The entity keys behind the integer identifiers the surface speaks in.
 *
 * <p>Every record linkwarden keeps carries a 32-bit key assigned in ascending order of creation,
 * and the system reads that ordering as chronology (SPEC-001 §2.1). The port keeps the integer and
 * derives the entity key from it, so a rule that orders by identifier orders the same way.
 */
public final class Ids {

  private Ids() {}

  public static String user(int id) {
    return "user-" + id;
  }

  public static String collection(int id) {
    return "collection-" + id;
  }

  public static String link(int id) {
    return "link-" + id;
  }

  public static String tag(int id) {
    return "tag-" + id;
  }

  public static String highlight(int id) {
    return "highlight-" + id;
  }

  public static String accessToken(int id) {
    return "token-" + id;
  }

  public static String rss(int id) {
    return "rss-" + id;
  }

  public static String subscription(int id) {
    return "subscription-" + id;
  }

  /** Every collection a person owns or is a member of. */
  public static String collectionsOf(int userId) {
    return "collections-of-" + userId;
  }

  /** Every collection whose parent is this one. */
  public static String childrenOf(int collectionId) {
    return "children-of-" + collectionId;
  }

  /** Every link held by this collection. */
  public static String linksOf(int collectionId) {
    return "links-of-" + collectionId;
  }

  /**
   * One run of the preservation pipeline over one link.
   *
   * <p>The instant is part of the key because a workflow instance is one run: a link offered to
   * the pipeline again after a run has finished needs an instance of its own.
   */
  public static String archiveRun(int linkId, java.time.Instant startedAt) {
    return "archive-" + linkId + "-" + startedAt.toEpochMilli();
  }

  /** Every feed an account subscribes to. */
  public static String feedsOf(int ownerId) {
    return "feeds-of-" + ownerId;
  }

  /** Every mark one person made on one link. */
  public static String highlightsOn(int linkId, int userId) {
    return "highlights-" + linkId + "-" + userId;
  }

  /** Every tag an account owns. */
  public static String tagsOf(int ownerId) {
    return "tags-of-" + ownerId;
  }

  /** Every link carrying one tag. */
  public static String linksWithTag(int tagId) {
    return "links-with-tag-" + tagId;
  }

  /** Which tag an owner holds under one name. SPEC-001 R40. */
  public static String tagNamed(int ownerId, String name) {
    return "tag-named-" + ownerId + "-" + (name == null ? "" : name);
  }

  /** Every access token an account holds, revoked or not. */
  public static String tokensOf(int userId) {
    return "tokens-of-" + userId;
  }

  /** Which access token row carries a presented token's identifier. SPEC-001 R10. */
  public static String tokenByIdentifier(String jti) {
    return "jti-" + jti;
  }

  /** The directory key a username is claimed under. SPEC-001 R16. */
  public static String usernameHolder(String username) {
    return "username-" + (username == null ? "" : username.toLowerCase());
  }

  /** The directory key an address is claimed under. SPEC-001 R16. */
  public static String emailHolder(String email) {
    return "email-" + (email == null ? "" : email.toLowerCase());
  }

  /**
   * Where the tokens outstanding for one address are kept. SPEC-001 R18-R22.
   *
   * <p>The two kinds are separate stores under the same address: a verification token must not
   * satisfy a password reset, and a shared store would make that a filter rather than a key.
   */
  public static String verificationTokens(String email) {
    return "verify-" + (email == null ? "" : email.toLowerCase());
  }

  public static String passwordResetTokens(String email) {
    return "reset-" + (email == null ? "" : email.toLowerCase());
  }

  public static int number(String entityKey) {
    return Integer.parseInt(entityKey.substring(entityKey.lastIndexOf('-') + 1));
  }
}

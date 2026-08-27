package io.akka.linkwarden.api;

import io.akka.linkwarden.domain.Permissions;
import io.akka.linkwarden.domain.Records;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What each record looks like on the wire.
 *
 * <p>These are the shapes a client already written against the original expects, down to which
 * fields are present: a link answered by {@code POST /api/v1/links} carries no {@code pinnedBy}
 * and one answered by {@code GET /api/v1/links/{id}} does, and the difference is not an oversight
 * to tidy — the interface reads the presence of that field.
 */
public final class Shapes {

  private Shapes() {}

  /**
   * An instant on the wire, to the millisecond.
   *
   * <p>Three decimal places always, because that is what a JavaScript ISO string carries and what
   * a client already written against the original parses; a platform clock with nanoseconds would
   * otherwise put nine there.
   */
  private static String iso(Instant instant) {
    return instant == null
        ? null
        : DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
            .withZone(ZoneOffset.UTC)
            .format(instant);
  }

  // ------------------------------------------------------------------
  // user
  // ------------------------------------------------------------------

  /** The whole account minus the password, which is what registration and the admin list give. */
  public static Map<String, Object> user(Records.User user) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("id", user.id());
    out.put("uuid", user.uuid());
    out.put("name", user.name());
    out.put("username", user.username());
    out.put("email", user.email());
    out.put("emailVerified", iso(user.emailVerified()));
    out.put("unverifiedNewEmail", user.unverifiedNewEmail());
    out.put("image", user.image());
    out.put("locale", user.locale());
    out.put("parentSubscriptionId", user.parentSubscriptionId());
    out.put("collectionOrder", user.collectionOrder());
    out.put("linksRouteTo", user.linksRouteTo());
    out.put("aiTaggingMethod", user.aiTaggingMethod());
    out.put("aiPredefinedTags", user.aiPredefinedTags());
    out.put("aiTagExistingLinks", user.aiTagExistingLinks());
    out.put("theme", user.theme());
    out.put("dismissedAnnouncementId", user.dismissedAnnouncementId());
    out.put("readableFontFamily", user.readableFontFamily());
    out.put("readableFontSize", user.readableFontSize());
    out.put("readableLineHeight", user.readableLineHeight());
    out.put("readableLineWidth", user.readableLineWidth());
    out.put("preventDuplicateLinks", user.preventDuplicateLinks());
    out.put("archiveAsScreenshot", user.archiveAsScreenshot());
    out.put("archiveAsMonolith", user.archiveAsMonolith());
    out.put("archiveAsPDF", user.archiveAsPDF());
    out.put("archiveAsReadable", user.archiveAsReadable());
    out.put("archiveAsWaybackMachine", user.archiveAsWaybackMachine());
    out.put("isPrivate", user.isPrivate());
    out.put("referredBy", user.referredBy());
    out.put("lastPickedAt", iso(user.lastPickedAt()));
    out.put("acceptPromotionalEmails", user.acceptPromotionalEmails());
    out.put("trialEndEmailSent", user.trialEndEmailSent());
    out.put("createdAt", iso(user.createdAt()));
    out.put("updatedAt", iso(user.updatedAt()));
    return out;
  }

  /** Which of the three routes is answering — they differ in the fields they carry. */
  public enum Account {
    /** {@code GET /users/{id}} and {@code /users/me}. */
    READ,
    /** {@code PUT /users/{id}}. */
    UPDATED,
    /** {@code PUT /users/{id}/preference}. */
    PREFERENCE
  }

  /**
   * The account as the two read routes and the two update routes give it.
   *
   * <p>{@code subscription} and {@code parentSubscription} are the rows a billing deployment
   * would have joined. The read route defaults the two fields it reads through, so they are
   * present and false/zero; the update routes pass the row's fields straight out, so with no
   * row every field is undefined and the object serialises empty.
   */
  public static Map<String, Object> userWithSubscription(
      Records.User user, boolean hasUnIndexedLinks, Account route) {
    Map<String, Object> out = user(user);
    Map<String, Object> subscription = new LinkedHashMap<>();
    if (route == Account.READ) {
      subscription.put("active", false);
      subscription.put("quantity", 0);
    }
    out.put("subscription", subscription);
    Map<String, Object> parent = new LinkedHashMap<>();
    parent.put("user", new LinkedHashMap<String, Object>());
    out.put("parentSubscription", parent);
    if (route != Account.PREFERENCE) {
      out.put("hasPassword", user.password() != null && !user.password().isEmpty());
      out.put("hasOAuthAccount", false);
    }
    if (route == Account.READ) out.put("hasUnIndexedLinks", hasUnIndexedLinks);
    out.put("dashboardSections", user.dashboardSections().stream().map(Shapes::section).toList());
    return out;
  }

  public static Map<String, Object> userWithSubscription(
      Records.User user, boolean hasUnIndexedLinks) {
    return userWithSubscription(user, hasUnIndexedLinks, Account.READ);
  }

  public static Map<String, Object> section(Records.DashboardSection section) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("id", section.id());
    out.put("userId", section.userId());
    out.put("collectionId", section.collectionId());
    out.put("type", section.type());
    out.put("order", section.order());
    return out;
  }

  /** SPEC-001 R41 — the six fields the public account route discloses and no others. */
  public static Map<String, Object> publicUser(Records.User user) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("id", user.id());
    out.put("name", user.name());
    out.put("username", user.username());
    out.put("image", user.image());
    out.put("archiveAsScreenshot", user.archiveAsScreenshot());
    out.put("archiveAsMonolith", user.archiveAsMonolith());
    out.put("archiveAsPDF", user.archiveAsPDF());
    return out;
  }

  // ------------------------------------------------------------------
  // collection
  // ------------------------------------------------------------------

  /** A member row, with the little of the member's account the interface shows beside it. */
  public static Map<String, Object> member(
      Permissions.Member m, Records.User user, int collectionId) {
    return member(m, user, collectionId, false);
  }

  /**
   * @param withIdentifier true on the update route, which selects the member's own identifier
   *     as well as the three fields the read route shows
   */
  public static Map<String, Object> member(
      Permissions.Member m, Records.User user, int collectionId, boolean withIdentifier) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("userId", m.userId());
    out.put("collectionId", collectionId);
    out.put("canCreate", m.canCreate());
    out.put("canUpdate", m.canUpdate());
    out.put("canDelete", m.canDelete());
    Map<String, Object> shown = new LinkedHashMap<>();
    if (withIdentifier) shown.put("id", m.userId());
    shown.put("username", user == null ? null : user.username());
    shown.put("name", user == null ? null : user.name());
    shown.put("image", user == null ? null : user.image());
    out.put("user", shown);
    return out;
  }

  public static Map<String, Object> collection(
      Records.Collection collection, long linkCount, List<Map<String, Object>> members) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("id", collection.id());
    out.put("name", collection.name());
    out.put("description", collection.description());
    out.put("icon", collection.icon());
    out.put("iconWeight", collection.iconWeight());
    out.put("color", collection.color());
    out.put("parentId", collection.parentId());
    out.put("isPublic", collection.isPublic());
    out.put("ownerId", collection.ownerId());
    out.put("createdById", collection.createdById());
    out.put("createdAt", iso(collection.createdAt()));
    out.put("updatedAt", iso(collection.updatedAt()));
    out.put("members", members);
    Map<String, Object> count = new LinkedHashMap<>();
    count.put("links", linkCount);
    out.put("_count", count);
    return out;
  }

  /** The collection as it appears nested inside a link: no members and no count. */
  public static Map<String, Object> shortCollection(Records.Collection collection) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("id", collection.id());
    out.put("name", collection.name());
    out.put("description", collection.description());
    out.put("icon", collection.icon());
    out.put("iconWeight", collection.iconWeight());
    out.put("color", collection.color());
    out.put("parentId", collection.parentId());
    out.put("isPublic", collection.isPublic());
    out.put("ownerId", collection.ownerId());
    out.put("createdById", collection.createdById());
    out.put("createdAt", iso(collection.createdAt()));
    out.put("updatedAt", iso(collection.updatedAt()));
    return out;
  }

  // ------------------------------------------------------------------
  // link
  // ------------------------------------------------------------------

  /**
   * @param omitText true for the three routes that leave the page's own text out of a link:
   *     the second dashboard, the search, and the export. Everywhere else it travels with
   *     the link, present and null when there is none.
   */
  /**
   * The link's own columns and nothing joined to it — what a bare row read or write returns.
   */
  public static Map<String, Object> bareLink(Records.Link link) {
    return linkColumns(link, false);
  }

  public static Map<String, Object> link(
      Records.Link link,
      Records.Collection collection,
      List<Records.Tag> tags,
      List<Integer> pinnedBy,
      boolean omitText) {
    Map<String, Object> out = linkColumns(link, omitText);
    out.put("tags", tags.stream().map(Shapes::tag).toList());
    if (collection != null) out.put("collection", shortCollection(collection));
    if (pinnedBy != null) {
      List<Map<String, Object>> pins = new ArrayList<>();
      for (int id : pinnedBy) {
        Map<String, Object> pin = new LinkedHashMap<>();
        pin.put("id", id);
        pins.add(pin);
      }
      out.put("pinnedBy", pins);
    }
    return out;
  }

  private static Map<String, Object> linkColumns(Records.Link link, boolean omitText) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("id", link.id());
    out.put("name", link.name());
    out.put("type", link.type());
    out.put("description", link.description());
    out.put("createdById", link.createdById());
    out.put("collectionId", link.collectionId());
    out.put("icon", link.icon());
    out.put("iconWeight", link.iconWeight());
    out.put("color", link.color());
    out.put("url", link.url());
    if (!omitText) out.put("textContent", link.textContent());
    out.put("preview", link.preview());
    out.put("image", link.image());
    out.put("pdf", link.pdf());
    out.put("readable", link.readable());
    out.put("monolith", link.monolith());
    out.put("clientSide", link.clientSide());
    out.put("aiTagged", link.aiTagged());
    out.put("metaDescription", link.metaDescription());
    out.put("indexVersion", link.indexVersion());
    out.put("lastPreserved", iso(link.lastPreserved()));
    out.put("importDate", iso(link.importDate()));
    out.put("createdAt", iso(link.createdAt()));
    out.put("updatedAt", iso(link.updatedAt()));
    return out;
  }

  // ------------------------------------------------------------------
  // tag, highlight, token, feed
  // ------------------------------------------------------------------

  public static Map<String, Object> tag(Records.Tag tag) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("id", tag.id());
    out.put("name", tag.name());
    out.put("ownerId", tag.ownerId());
    out.put("archiveAsScreenshot", tag.archiveAsScreenshot());
    out.put("archiveAsMonolith", tag.archiveAsMonolith());
    out.put("archiveAsPDF", tag.archiveAsPDF());
    out.put("archiveAsReadable", tag.archiveAsReadable());
    out.put("archiveAsWaybackMachine", tag.archiveAsWaybackMachine());
    out.put("aiTag", tag.aiTag());
    out.put("aiGenerated", tag.aiGenerated());
    out.put("createdAt", iso(tag.createdAt()));
    out.put("updatedAt", iso(tag.updatedAt()));
    return out;
  }

  public static Map<String, Object> tagWithCount(Records.Tag tag, long linkCount) {
    Map<String, Object> out = tag(tag);
    Map<String, Object> count = new LinkedHashMap<>();
    count.put("links", linkCount);
    out.put("_count", count);
    return out;
  }

  public static Map<String, Object> highlight(Records.Highlight highlight) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("id", highlight.id());
    out.put("linkId", highlight.linkId());
    out.put("userId", highlight.userId());
    out.put("color", highlight.color());
    out.put("comment", highlight.comment());
    out.put("startOffset", highlight.startOffset());
    out.put("endOffset", highlight.endOffset());
    out.put("text", highlight.text());
    out.put("createdAt", iso(highlight.createdAt()));
    out.put("updatedAt", iso(highlight.updatedAt()));
    return out;
  }

  /** SPEC-001 R14 — the listing discloses no identifier a presented token could be built from. */
  public static Map<String, Object> tokenSummary(Records.AccessToken token) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("id", token.id());
    out.put("name", token.name());
    out.put("isSession", token.isSession());
    out.put("expires", iso(token.expires()));
    out.put("createdAt", iso(token.createdAt()));
    return out;
  }

  public static Map<String, Object> token(Records.AccessToken token) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("id", token.id());
    out.put("name", token.name());
    out.put("userId", token.userId());
    out.put("token", token.jti());
    out.put("revoked", token.revoked());
    out.put("isSession", token.isSession());
    out.put("expires", iso(token.expires()));
    out.put("lastUsedAt", iso(token.lastUsedAt()));
    out.put("createdAt", iso(token.createdAt()));
    out.put("updatedAt", iso(token.updatedAt()));
    return out;
  }

  public static Map<String, Object> feed(
      Records.RssSubscription subscription, String collectionName) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("id", subscription.id());
    out.put("url", subscription.url());
    out.put("name", subscription.name());
    out.put("lastBuildDate", iso(subscription.lastBuildDate()));
    out.put("collectionId", subscription.collectionId());
    out.put("ownerId", subscription.ownerId());
    out.put("createdAt", iso(subscription.createdAt()));
    out.put("updatedAt", iso(subscription.updatedAt()));
    if (collectionName != null) {
      Map<String, Object> collection = new LinkedHashMap<>();
      collection.put("name", collectionName);
      out.put("collection", collection);
    }
    return out;
  }
}

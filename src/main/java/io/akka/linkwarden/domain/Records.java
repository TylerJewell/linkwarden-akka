package io.akka.linkwarden.domain;

import java.time.Instant;
import java.util.List;

/**
 * The eight records a Linkwarden instance keeps, as SPEC-001 §2.2 lists them.
 *
 * <p>They are gathered in one file because they are one schema: every field's nullability, its
 * default and the enumeration it is drawn from are decided together, and a reader checking the
 * port against §2.2 reads one page rather than eight.
 *
 * <p>Nullability here is the original's. It is deliberately <em>not</em> tidied: {@code
 * Link.description} is a non-null empty string while {@code Link.url} is genuinely absent, and the
 * two are different answers on the wire.
 */
public final class Records {

  private Records() {}

  /** SPEC-001 §2.2 — a person with an account. */
  public record User(
      int id,
      String uuid,
      String name,
      String username,
      String email,
      Instant emailVerified,
      String unverifiedNewEmail,
      String image,
      String password,
      String locale,
      List<Integer> collectionOrder,
      String linksRouteTo,
      String aiTaggingMethod,
      List<String> aiPredefinedTags,
      boolean aiTagExistingLinks,
      String theme,
      String dismissedAnnouncementId,
      String readableFontFamily,
      String readableFontSize,
      String readableLineHeight,
      String readableLineWidth,
      boolean preventDuplicateLinks,
      boolean archiveAsScreenshot,
      boolean archiveAsMonolith,
      boolean archiveAsPDF,
      boolean archiveAsReadable,
      boolean archiveAsWaybackMachine,
      boolean isPrivate,
      String referredBy,
      Instant lastPickedAt,
      boolean acceptPromotionalEmails,
      boolean trialEndEmailSent,
      Integer parentSubscriptionId,
      List<DashboardSection> dashboardSections,
      Instant createdAt,
      Instant updatedAt,
      boolean deleted) {

    /** The defaults the original writes on a fresh account, and the three dashboard sections. */
    public static User fresh(
        int id, String uuid, String name, String username, String email, String password,
        boolean emailVerified, boolean acceptPromotionalEmails, Instant now) {
      return new User(
          id, uuid, name, username, email, emailVerified ? now : null, null, null, password,
          "en", List.of(), "ORIGINAL", "DISABLED", List.of(), false, "dark", null,
          "sans-serif", "20px", "1.8", "normal", false,
          true, true, true, true, false,
          false, null, null, acceptPromotionalEmails, false, null,
          List.of(
              new DashboardSection(1, id, null, "STATS", 0),
              new DashboardSection(2, id, null, "RECENT_LINKS", 1),
              new DashboardSection(3, id, null, "PINNED_LINKS", 2)),
          now, now, false);
    }

    /** A mutable copy, so an update names the fields it changes rather than all thirty-seven. */
    public Builder copy() {
      return new Builder(this);
    }

    /** Fields are public because this exists only to be written to and immediately built. */
    public static final class Builder {
      public int id;
      public String uuid;
      public String name;
      public String username;
      public String email;
      public Instant emailVerified;
      public String unverifiedNewEmail;
      public String image;
      public String password;
      public String locale;
      public List<Integer> collectionOrder;
      public String linksRouteTo;
      public String aiTaggingMethod;
      public List<String> aiPredefinedTags;
      public boolean aiTagExistingLinks;
      public String theme;
      public String dismissedAnnouncementId;
      public String readableFontFamily;
      public String readableFontSize;
      public String readableLineHeight;
      public String readableLineWidth;
      public boolean preventDuplicateLinks;
      public boolean archiveAsScreenshot;
      public boolean archiveAsMonolith;
      public boolean archiveAsPDF;
      public boolean archiveAsReadable;
      public boolean archiveAsWaybackMachine;
      public boolean isPrivate;
      public String referredBy;
      public Instant lastPickedAt;
      public boolean acceptPromotionalEmails;
      public boolean trialEndEmailSent;
      public Integer parentSubscriptionId;
      public List<DashboardSection> dashboardSections;
      public Instant createdAt;
      public Instant updatedAt;
      public boolean deleted;

      Builder(User user) {
        id = user.id();
        uuid = user.uuid();
        name = user.name();
        username = user.username();
        email = user.email();
        emailVerified = user.emailVerified();
        unverifiedNewEmail = user.unverifiedNewEmail();
        image = user.image();
        password = user.password();
        locale = user.locale();
        collectionOrder = user.collectionOrder();
        linksRouteTo = user.linksRouteTo();
        aiTaggingMethod = user.aiTaggingMethod();
        aiPredefinedTags = user.aiPredefinedTags();
        aiTagExistingLinks = user.aiTagExistingLinks();
        theme = user.theme();
        dismissedAnnouncementId = user.dismissedAnnouncementId();
        readableFontFamily = user.readableFontFamily();
        readableFontSize = user.readableFontSize();
        readableLineHeight = user.readableLineHeight();
        readableLineWidth = user.readableLineWidth();
        preventDuplicateLinks = user.preventDuplicateLinks();
        archiveAsScreenshot = user.archiveAsScreenshot();
        archiveAsMonolith = user.archiveAsMonolith();
        archiveAsPDF = user.archiveAsPDF();
        archiveAsReadable = user.archiveAsReadable();
        archiveAsWaybackMachine = user.archiveAsWaybackMachine();
        isPrivate = user.isPrivate();
        referredBy = user.referredBy();
        lastPickedAt = user.lastPickedAt();
        acceptPromotionalEmails = user.acceptPromotionalEmails();
        trialEndEmailSent = user.trialEndEmailSent();
        parentSubscriptionId = user.parentSubscriptionId();
        dashboardSections = user.dashboardSections();
        createdAt = user.createdAt();
        updatedAt = user.updatedAt();
        deleted = user.deleted();
      }

      public User build() {
        return new User(
            id, uuid, name, username, email, emailVerified, unverifiedNewEmail, image, password,
            locale, collectionOrder, linksRouteTo, aiTaggingMethod, aiPredefinedTags,
            aiTagExistingLinks, theme, dismissedAnnouncementId, readableFontFamily,
            readableFontSize, readableLineHeight, readableLineWidth, preventDuplicateLinks,
            archiveAsScreenshot, archiveAsMonolith, archiveAsPDF, archiveAsReadable,
            archiveAsWaybackMachine, isPrivate, referredBy, lastPickedAt,
            acceptPromotionalEmails, trialEndEmailSent, parentSubscriptionId, dashboardSections,
            createdAt, updatedAt, deleted);
      }
    }

    public ArchivalSettings archivalSettings() {
      return new ArchivalSettings(
          archiveAsScreenshot, archiveAsMonolith, archiveAsPDF, archiveAsReadable,
          archiveAsWaybackMachine, false);
    }
  }

  /** One row of the dashboard's layout. */
  public record DashboardSection(int id, int userId, Integer collectionId, String type, int order) {}

  /** SPEC-001 §2.2 — a collection, with its members held on it rather than beside it. */
  public record Collection(
      int id,
      String name,
      String description,
      String icon,
      String iconWeight,
      String color,
      Integer parentId,
      boolean isPublic,
      int ownerId,
      Integer createdById,
      List<Permissions.Member> members,
      Instant createdAt,
      Instant updatedAt,
      boolean deleted) {

    public static Collection fresh(
        int id, String name, String description, String icon, String iconWeight, String color,
        Integer parentId, int ownerId, Integer createdById, List<Permissions.Member> members,
        Instant now) {
      return new Collection(
          id,
          name,
          description == null ? "" : description,
          icon,
          iconWeight,
          color == null ? "#0ea5e9" : color,
          parentId,
          false,
          ownerId,
          createdById,
          members == null ? List.of() : List.copyOf(members),
          now,
          now,
          false);
    }

    public Permissions.Subject asSubject() {
      return new Permissions.Subject(id, ownerId, members);
    }

    /** A mutable copy, so an update names the fields it changes rather than all of them. */
    public Builder copy() {
      return new Builder(this);
    }

    /** Fields are public because this exists only to be written to and immediately built. */
    public static final class Builder {
      public int id;
      public String name;
      public String description;
      public String icon;
      public String iconWeight;
      public String color;
      public Integer parentId;
      public boolean isPublic;
      public int ownerId;
      public Integer createdById;
      public List<Permissions.Member> members;
      public Instant createdAt;
      public Instant updatedAt;
      public boolean deleted;

      Builder(Collection value) {
        id = value.id();
        name = value.name();
        description = value.description();
        icon = value.icon();
        iconWeight = value.iconWeight();
        color = value.color();
        parentId = value.parentId();
        isPublic = value.isPublic();
        ownerId = value.ownerId();
        createdById = value.createdById();
        members = value.members();
        createdAt = value.createdAt();
        updatedAt = value.updatedAt();
        deleted = value.deleted();
      }

      public Collection build() {
        return new Collection(
            id, name, description, icon, iconWeight, color, parentId, isPublic, ownerId,
            createdById, members, createdAt, updatedAt, deleted);
      }
    }

  }

  /** SPEC-001 §2.2 — a saved link and everything written back about it. */
  public record Link(
      int id,
      String name,
      String type,
      String description,
      int collectionId,
      Integer createdById,
      String icon,
      String iconWeight,
      String color,
      String url,
      String textContent,
      String preview,
      String image,
      String pdf,
      String readable,
      String monolith,
      boolean clientSide,
      boolean aiTagged,
      String metaDescription,
      Integer indexVersion,
      Instant lastPreserved,
      Instant importDate,
      List<Integer> tagIds,
      List<Integer> pinnedBy,
      Instant createdAt,
      Instant updatedAt,
      boolean deleted) {

    public static final String UNAVAILABLE = "unavailable";

    public static Link fresh(
        int id, String name, String type, String description, int collectionId, Integer createdById,
        String url, List<Integer> tagIds, Instant importDate, Instant now) {
      return new Link(
          id,
          name == null ? "" : name,
          type == null ? "url" : type,
          description == null ? "" : description,
          collectionId,
          createdById,
          null, null, null,
          url,
          null, null, null, null, null, null,
          false,
          false,
          null,
          null,
          null,
          importDate,
          tagIds == null ? List.of() : List.copyOf(tagIds),
          List.of(),
          now,
          now,
          false);
    }

    /** SPEC-001 R39 — a link whose url may not be fetched is finished before it is offered. */
    public Link unfetchable(Instant now) {
      return new Link(
          id, name, type, description, collectionId, createdById, icon, iconWeight, color, url,
          textContent, UNAVAILABLE, UNAVAILABLE, UNAVAILABLE, UNAVAILABLE, UNAVAILABLE,
          clientSide, aiTagged, metaDescription, null, now, importDate, tagIds, pinnedBy,
          createdAt, now, deleted);
    }

    /** SPEC-001 R43 and R54 — everything an attempt wrote, cleared. */
    public Link withPreservationCleared(Instant now) {
      return new Link(
          id, name, type, description, collectionId, createdById, icon, iconWeight, color, url,
          textContent, null, null, null, null, null, clientSide, aiTagged, metaDescription,
          null, null, importDate, tagIds, pinnedBy, createdAt, now, deleted);
    }

    public String formatValue(String field) {
      return switch (field) {
        case "image" -> image;
        case "pdf" -> pdf;
        case "readable" -> readable;
        case "monolith" -> monolith;
        case "preview" -> preview;
        default -> null;
      };
    }

    /** A mutable copy, so an update names the fields it changes rather than all of them. */
    public Builder copy() {
      return new Builder(this);
    }

    /** Fields are public because this exists only to be written to and immediately built. */
    public static final class Builder {
      public int id;
      public String name;
      public String type;
      public String description;
      public int collectionId;
      public Integer createdById;
      public String icon;
      public String iconWeight;
      public String color;
      public String url;
      public String textContent;
      public String preview;
      public String image;
      public String pdf;
      public String readable;
      public String monolith;
      public boolean clientSide;
      public boolean aiTagged;
      public String metaDescription;
      public Integer indexVersion;
      public Instant lastPreserved;
      public Instant importDate;
      public List<Integer> tagIds;
      public List<Integer> pinnedBy;
      public Instant createdAt;
      public Instant updatedAt;
      public boolean deleted;

      Builder(Link value) {
        id = value.id();
        name = value.name();
        type = value.type();
        description = value.description();
        collectionId = value.collectionId();
        createdById = value.createdById();
        icon = value.icon();
        iconWeight = value.iconWeight();
        color = value.color();
        url = value.url();
        textContent = value.textContent();
        preview = value.preview();
        image = value.image();
        pdf = value.pdf();
        readable = value.readable();
        monolith = value.monolith();
        clientSide = value.clientSide();
        aiTagged = value.aiTagged();
        metaDescription = value.metaDescription();
        indexVersion = value.indexVersion();
        lastPreserved = value.lastPreserved();
        importDate = value.importDate();
        tagIds = value.tagIds();
        pinnedBy = value.pinnedBy();
        createdAt = value.createdAt();
        updatedAt = value.updatedAt();
        deleted = value.deleted();
      }

      public Link build() {
        return new Link(
            id, name, type, description, collectionId, createdById, icon, iconWeight, color,
            url, textContent, preview, image, pdf, readable, monolith, clientSide, aiTagged,
            metaDescription, indexVersion, lastPreserved, importDate, tagIds, pinnedBy,
            createdAt, updatedAt, deleted);
      }
    }

  }

  /** SPEC-001 §2.2 — a tag, owned by one account and unique by name within it. */
  public record Tag(
      int id,
      String name,
      int ownerId,
      Boolean archiveAsScreenshot,
      Boolean archiveAsMonolith,
      Boolean archiveAsPDF,
      Boolean archiveAsReadable,
      Boolean archiveAsWaybackMachine,
      Boolean aiTag,
      boolean aiGenerated,
      Instant createdAt,
      Instant updatedAt,
      boolean deleted) {

    public static Tag fresh(int id, String name, int ownerId, boolean aiGenerated, Instant now) {
      return new Tag(id, name, ownerId, null, null, null, null, null, null, aiGenerated, now, now,
          false);
    }

    /** SPEC-001 R50 — a tag takes part in the union only when it carries an archival field. */
    public boolean isArchival() {
      return archiveAsScreenshot != null
          || archiveAsMonolith != null
          || archiveAsPDF != null
          || archiveAsReadable != null
          || archiveAsWaybackMachine != null
          || aiTag != null;
    }

    /** The same tag as the preservation rules read it, which know nothing of owners or keys. */
    public io.akka.linkwarden.domain.Tag asArchivalTag() {
      return new io.akka.linkwarden.domain.Tag(
          name, archiveAsScreenshot, archiveAsMonolith, archiveAsPDF, archiveAsReadable,
          archiveAsWaybackMachine, aiTag);
    }

    public ArchivalSettings asSettings() {
      return new ArchivalSettings(
          Boolean.TRUE.equals(archiveAsScreenshot),
          Boolean.TRUE.equals(archiveAsMonolith),
          Boolean.TRUE.equals(archiveAsPDF),
          Boolean.TRUE.equals(archiveAsReadable),
          Boolean.TRUE.equals(archiveAsWaybackMachine),
          Boolean.TRUE.equals(aiTag));
    }

    /** A mutable copy, so an update names the fields it changes rather than all of them. */
    public Builder copy() {
      return new Builder(this);
    }

    /** Fields are public because this exists only to be written to and immediately built. */
    public static final class Builder {
      public int id;
      public String name;
      public int ownerId;
      public Boolean archiveAsScreenshot;
      public Boolean archiveAsMonolith;
      public Boolean archiveAsPDF;
      public Boolean archiveAsReadable;
      public Boolean archiveAsWaybackMachine;
      public Boolean aiTag;
      public boolean aiGenerated;
      public Instant createdAt;
      public Instant updatedAt;
      public boolean deleted;

      Builder(Tag value) {
        id = value.id();
        name = value.name();
        ownerId = value.ownerId();
        archiveAsScreenshot = value.archiveAsScreenshot();
        archiveAsMonolith = value.archiveAsMonolith();
        archiveAsPDF = value.archiveAsPDF();
        archiveAsReadable = value.archiveAsReadable();
        archiveAsWaybackMachine = value.archiveAsWaybackMachine();
        aiTag = value.aiTag();
        aiGenerated = value.aiGenerated();
        createdAt = value.createdAt();
        updatedAt = value.updatedAt();
        deleted = value.deleted();
      }

      public Tag build() {
        return new Tag(
            id, name, ownerId, archiveAsScreenshot, archiveAsMonolith, archiveAsPDF,
            archiveAsReadable, archiveAsWaybackMachine, aiTag, aiGenerated, createdAt,
            updatedAt, deleted);
      }
    }

  }

  /** SPEC-001 §2.2 — a passage of a link somebody marked. */
  public record Highlight(
      int id,
      int linkId,
      int userId,
      String color,
      String comment,
      int startOffset,
      int endOffset,
      String text,
      Instant createdAt,
      Instant updatedAt,
      boolean deleted) {}

  /** SPEC-001 §2.2 — an API token or a session; the two differ only by a flag. */
  public record AccessToken(
      int id,
      String name,
      int userId,
      String jti,
      boolean revoked,
      boolean isSession,
      Instant expires,
      Instant lastUsedAt,
      Instant createdAt,
      Instant updatedAt) {}

  /** SPEC-001 §2.2 — a feed polled into a collection. */
  public record RssSubscription(
      int id,
      String url,
      String name,
      Instant lastBuildDate,
      int collectionId,
      int ownerId,
      Instant createdAt,
      Instant updatedAt,
      boolean deleted) {}
}

package io.akka.linkwarden.application;

import akka.Done;
import akka.javasdk.annotations.Component;
import akka.javasdk.keyvalueentity.KeyValueEntity;
import io.akka.linkwarden.domain.Records;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** One account. SPEC-001 §2.2, R15–R16, R38–R40, R71–R72. */
@Component(id = "user")
public class UserEntity extends KeyValueEntity<Records.User> {

  public record Create(
      int id,
      String uuid,
      String name,
      String username,
      String email,
      String hashedPassword,
      boolean emailVerified,
      boolean acceptPromotionalEmails,
      Instant now) {}

  /**
   * The settings route's whole body.
   *
   * <p>Every field is nullable and an absent one leaves what is stored alone, which is what makes
   * one command serve a form that submits three fields and a form that submits twenty.
   */
  public record UpdateSettings(
      String name,
      String username,
      String email,
      String image,
      String hashedPassword,
      String locale,
      List<Integer> collectionOrder,
      String linksRouteTo,
      String aiTaggingMethod,
      List<String> aiPredefinedTags,
      Boolean aiTagExistingLinks,
      Boolean archiveAsScreenshot,
      Boolean archiveAsMonolith,
      Boolean archiveAsPDF,
      Boolean archiveAsReadable,
      Boolean archiveAsWaybackMachine,
      Boolean isPrivate,
      Boolean preventDuplicateLinks,
      String referredBy,
      Instant now) {}

  public record UpdatePreference(
      String theme,
      String readableFontFamily,
      String readableFontSize,
      String readableLineHeight,
      String readableLineWidth,
      String dismissedAnnouncementId,
      Instant now) {}

  public record SetDashboardSections(List<Records.DashboardSection> sections, Instant now) {}

  public record CollectionRef(int collectionId, Instant now) {}

  public record PendingEmail(String newEmail, Instant now) {}

  public Effect<Done> create(Create cmd) {
    if (currentState() != null) return effects().error("Email or Username already exists.");
    return effects()
        .updateState(
            Records.User.fresh(
                cmd.id(), cmd.uuid(), cmd.name(), cmd.username(), cmd.email(),
                cmd.hashedPassword(), cmd.emailVerified(), cmd.acceptPromotionalEmails(),
                cmd.now()))
        .thenReply(Done.getInstance());
  }

  public ReadOnlyEffect<Records.User> get() {
    Records.User user = currentState();
    if (user == null || user.deleted()) return effects().error("User not found.");
    return effects().reply(user);
  }

  public Effect<Records.User> updateSettings(UpdateSettings cmd) {
    Records.User user = currentState();
    if (user == null || user.deleted()) return effects().error("User not found.");

    Records.User.Builder b = user.copy();
    if (cmd.name() != null) b.name = cmd.name();
    if (cmd.username() != null) b.username = cmd.username();
    if (cmd.email() != null) b.email = cmd.email();
    if (cmd.image() != null) b.image = cmd.image();
    if (cmd.hashedPassword() != null) b.password = cmd.hashedPassword();
    if (cmd.locale() != null) b.locale = cmd.locale();
    if (cmd.collectionOrder() != null) b.collectionOrder = List.copyOf(cmd.collectionOrder());
    if (cmd.linksRouteTo() != null) b.linksRouteTo = cmd.linksRouteTo();
    if (cmd.aiTaggingMethod() != null) b.aiTaggingMethod = cmd.aiTaggingMethod();
    if (cmd.aiPredefinedTags() != null) b.aiPredefinedTags = List.copyOf(cmd.aiPredefinedTags());
    if (cmd.aiTagExistingLinks() != null) b.aiTagExistingLinks = cmd.aiTagExistingLinks();
    if (cmd.archiveAsScreenshot() != null) b.archiveAsScreenshot = cmd.archiveAsScreenshot();
    if (cmd.archiveAsMonolith() != null) b.archiveAsMonolith = cmd.archiveAsMonolith();
    if (cmd.archiveAsPDF() != null) b.archiveAsPDF = cmd.archiveAsPDF();
    if (cmd.archiveAsReadable() != null) b.archiveAsReadable = cmd.archiveAsReadable();
    if (cmd.archiveAsWaybackMachine() != null) {
      b.archiveAsWaybackMachine = cmd.archiveAsWaybackMachine();
    }
    if (cmd.isPrivate() != null) b.isPrivate = cmd.isPrivate();
    if (cmd.preventDuplicateLinks() != null) b.preventDuplicateLinks = cmd.preventDuplicateLinks();
    // Written once: an account that already names a referrer keeps the one it has.
    if (b.referredBy == null && cmd.referredBy() != null) b.referredBy = cmd.referredBy();
    b.updatedAt = cmd.now();

    Records.User updated = b.build();
    return effects().updateState(updated).thenReply(updated);
  }

  public Effect<Records.User> updatePreference(UpdatePreference cmd) {
    Records.User user = currentState();
    if (user == null || user.deleted()) return effects().error("User not found.");

    Records.User.Builder b = user.copy();
    if (cmd.theme() != null) b.theme = cmd.theme();
    if (cmd.readableFontFamily() != null) b.readableFontFamily = cmd.readableFontFamily();
    if (cmd.readableFontSize() != null) b.readableFontSize = cmd.readableFontSize();
    if (cmd.readableLineHeight() != null) b.readableLineHeight = cmd.readableLineHeight();
    if (cmd.readableLineWidth() != null) b.readableLineWidth = cmd.readableLineWidth();
    if (cmd.dismissedAnnouncementId() != null) {
      b.dismissedAnnouncementId = cmd.dismissedAnnouncementId();
    }
    b.updatedAt = cmd.now();

    Records.User updated = b.build();
    return effects().updateState(updated).thenReply(updated);
  }

  public Effect<Records.User> setDashboardSections(SetDashboardSections cmd) {
    Records.User user = currentState();
    if (user == null || user.deleted()) return effects().error("User not found.");
    Records.User.Builder b = user.copy();
    b.dashboardSections = List.copyOf(cmd.sections());
    b.updatedAt = cmd.now();
    Records.User updated = b.build();
    return effects().updateState(updated).thenReply(updated);
  }

  public Effect<Done> appendCollection(CollectionRef cmd) {
    Records.User user = currentState();
    if (user == null || user.deleted()) return effects().error("User not found.");
    List<Integer> order = new ArrayList<>(user.collectionOrder());
    if (!order.contains(cmd.collectionId())) order.add(cmd.collectionId());
    Records.User.Builder b = user.copy();
    b.collectionOrder = List.copyOf(order);
    b.updatedAt = cmd.now();
    return effects().updateState(b.build()).thenReply(Done.getInstance());
  }

  /**
   * Takes a collection out of the order and out of the dashboard, closing the gap the removed
   * section leaves behind it. SPEC-001 R32.
   */
  public Effect<Done> removeCollection(CollectionRef cmd) {
    Records.User user = currentState();
    if (user == null || user.deleted()) return effects().error("User not found.");

    List<Integer> order = new ArrayList<>(user.collectionOrder());
    order.removeIf(id -> id == cmd.collectionId());

    List<Records.DashboardSection> sections = new ArrayList<>(user.dashboardSections());
    Records.DashboardSection removed =
        sections.stream()
            .filter(s -> s.collectionId() != null && s.collectionId() == cmd.collectionId())
            .findFirst()
            .orElse(null);
    if (removed != null) {
      sections.remove(removed);
      sections =
          new ArrayList<>(
              sections.stream()
                  .map(
                      s ->
                          s.order() > removed.order()
                              ? new Records.DashboardSection(
                                  s.id(), s.userId(), s.collectionId(), s.type(), s.order() - 1)
                              : s)
                  .toList());
    }

    Records.User.Builder b = user.copy();
    b.collectionOrder = List.copyOf(order);
    b.dashboardSections = List.copyOf(sections);
    b.updatedAt = cmd.now();
    return effects().updateState(b.build()).thenReply(Done.getInstance());
  }

  public Effect<Done> markPicked(Instant now) {
    Records.User user = currentState();
    if (user == null || user.deleted()) return effects().error("User not found.");
    Records.User.Builder b = user.copy();
    b.lastPickedAt = now;
    b.updatedAt = now;
    return effects().updateState(b.build()).thenReply(Done.getInstance());
  }

  /** SPEC-001 R19 — a reset changes the password and nothing else the account carries. */
  public record NewPassword(String hashedPassword, Instant now) {}

  public Effect<Done> setPassword(NewPassword cmd) {
    Records.User user = currentState();
    if (user == null || user.deleted()) return effects().error("User not found.");
    Records.User.Builder b = user.copy();
    b.password = cmd.hashedPassword();
    b.updatedAt = cmd.now();
    return effects().updateState(b.build()).thenReply(Done.getInstance());
  }

  public Effect<Done> verifyEmail(Instant now) {
    Records.User user = currentState();
    if (user == null || user.deleted()) return effects().error("User not found.");
    Records.User.Builder b = user.copy();
    b.emailVerified = now;
    b.updatedAt = now;
    return effects().updateState(b.build()).thenReply(Done.getInstance());
  }

  /** Records an address the account has asked to move to but has not yet proved it holds. */
  public Effect<Done> requestEmailChange(PendingEmail cmd) {
    Records.User user = currentState();
    if (user == null || user.deleted()) return effects().error("User not found.");
    Records.User.Builder b = user.copy();
    b.unverifiedNewEmail = cmd.newEmail();
    b.updatedAt = cmd.now();
    return effects().updateState(b.build()).thenReply(Done.getInstance());
  }

  public Effect<Done> confirmEmailChange(PendingEmail cmd) {
    Records.User user = currentState();
    if (user == null || user.deleted()) return effects().error("User not found.");
    Records.User.Builder b = user.copy();
    b.email = cmd.newEmail();
    b.unverifiedNewEmail = null;
    b.updatedAt = cmd.now();
    return effects().updateState(b.build()).thenReply(Done.getInstance());
  }

  /**
   * A deleted account keeps its key and nothing else.
   *
   * <p>The key stays because the integer identifiers are handed out by a counter that never goes
   * backwards, and a view still holds rows naming it; wiping the fields is what makes the account
   * gone as far as every rule that reads one is concerned.
   */
  public Effect<Done> delete(Instant now) {
    Records.User user = currentState();
    if (user == null || user.deleted()) return effects().reply(Done.getInstance());
    Records.User.Builder b = user.copy();
    b.name = null;
    b.username = null;
    b.email = null;
    b.emailVerified = null;
    b.unverifiedNewEmail = null;
    b.image = null;
    b.password = null;
    b.collectionOrder = List.of();
    b.aiPredefinedTags = List.of();
    b.dashboardSections = List.of();
    b.parentSubscriptionId = null;
    b.deleted = true;
    b.updatedAt = now;
    return effects().updateState(b.build()).thenReply(Done.getInstance());
  }
}

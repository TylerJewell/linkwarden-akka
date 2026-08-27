package io.akka.linkwarden.domain;

import java.time.Instant;

/**
 * How many links an account may hold. SPEC-001 R73–R74.
 *
 * <p>The rule is a decision over facts that are gathered elsewhere, so it is written as a function
 * of those facts: whether billing is on, what subscription the account sits under, how many links
 * already exist, and how many are being added. A test drives it without a store or a clock.
 */
public final class Capacity {

  /** Everything the rule reads. A null subscription means the account has none. */
  public record Account(
      Integer parentSubscriptionId,
      Integer ownSubscriptionId,
      Integer ownSubscriptionQuantity,
      String ownSubscriptionProvider,
      Instant createdAt) {}

  private Capacity() {}

  /**
   * @param ownLinks the account's own links
   * @param organisationLinks every link belonging to the account's subscription, the account's own
   *     included
   * @param childCount the number of accounts attached to the parent subscription
   */
  public static boolean hasPassedLimit(
      Account account,
      int adding,
      int ownLinks,
      int organisationLinks,
      int childCount,
      Config config,
      Instant now) {

    int maximum = config.maxLinksPerUser();
    boolean billing = config.billingEnabled();

    if (!billing && !"STRIPE".equals(account.ownSubscriptionProvider())) {
      return maximum - (adding + ownLinks) < 0;
    }
    if (account.createdAt() == null) return true;

    long trialDays = config.number("NEXT_PUBLIC_TRIAL_PERIOD_DAYS", Config.DEFAULT_TRIAL_PERIOD_DAYS);
    long trialEnd = account.createdAt().toEpochMilli() + (1 + trialDays) * 86_400_000L;
    long daysLeft = Math.floorDiv(trialEnd - now.toEpochMilli(), 86_400_000L);

    if (!config.flag("NEXT_PUBLIC_REQUIRE_CC") && daysLeft > 0) {
      return maximum - (adding + ownLinks) < 0;
    }

    Integer subscriptionId =
        account.parentSubscriptionId() != null
            ? account.parentSubscriptionId()
            : account.ownSubscriptionId();
    Integer quantity = account.ownSubscriptionQuantity();

    if (subscriptionId == null || quantity == null || quantity == 0) return true;

    if (account.parentSubscriptionId() != null && childCount + 1 > quantity) return true;

    if (account.parentSubscriptionId() != null || quantity > 1) {
      long totalCapacity = (long) quantity * maximum;
      return totalCapacity - (adding + organisationLinks) < 0;
    }
    return maximum - (adding + ownLinks) < 0;
  }
}

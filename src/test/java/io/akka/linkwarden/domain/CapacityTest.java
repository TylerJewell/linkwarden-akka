package io.akka.linkwarden.domain;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.akka.linkwarden.domain.Capacity.Account;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** SPEC-001 R73–R74: how many links an account may hold. */
class CapacityTest {

  private static final Instant NOW = Instant.parse("2026-08-26T12:00:00Z");

  private static Config config(String... pairs) {
    Map<String, String> env = new java.util.LinkedHashMap<>();
    for (int i = 0; i < pairs.length; i += 2) env.put(pairs[i], pairs[i + 1]);
    return new Config(env);
  }

  private static Account plain() {
    return new Account(null, null, null, null, NOW.minusSeconds(86_400));
  }

  @Test
  void withBillingOffOnlyTheAccountsOwnLinksAreCounted() {
    Config config = config("MAX_LINKS_PER_USER", "10");
    assertFalse(Capacity.hasPassedLimit(plain(), 1, 9, 9, 0, config, NOW));
    assertTrue(Capacity.hasPassedLimit(plain(), 2, 9, 9, 0, config, NOW));
  }

  @Test
  void theBoundaryIsInclusive() {
    Config config = config("MAX_LINKS_PER_USER", "10");
    assertFalse(Capacity.hasPassedLimit(plain(), 1, 9, 9, 0, config, NOW));
    assertFalse(Capacity.hasPassedLimit(plain(), 0, 10, 10, 0, config, NOW));
    assertTrue(Capacity.hasPassedLimit(plain(), 1, 10, 10, 0, config, NOW));
  }

  @Test
  void anAccountInsideItsTrialIsMeasuredAgainstThePerUserMaximum() {
    Config config =
        config("STRIPE_SECRET_KEY", "sk", "MAX_LINKS_PER_USER", "10",
            "NEXT_PUBLIC_TRIAL_PERIOD_DAYS", "14");
    Account inTrial = new Account(null, null, null, null, NOW.minusSeconds(86_400));
    assertFalse(Capacity.hasPassedLimit(inTrial, 1, 5, 5, 0, config, NOW));
    assertTrue(Capacity.hasPassedLimit(inTrial, 6, 5, 5, 0, config, NOW));
  }

  @Test
  void anExpiredTrialWithNoSubscriptionIsRefusedOutright() {
    Config config = config("STRIPE_SECRET_KEY", "sk", "NEXT_PUBLIC_TRIAL_PERIOD_DAYS", "14");
    Account expired = new Account(null, null, null, null, NOW.minusSeconds(40L * 86_400));
    assertTrue(Capacity.hasPassedLimit(expired, 1, 0, 0, 0, config, NOW));
  }

  @Test
  void anAccountRequiredToGiveACardHasNoTrialAllowance() {
    Config config =
        config("STRIPE_SECRET_KEY", "sk", "NEXT_PUBLIC_REQUIRE_CC", "true");
    Account fresh = new Account(null, null, null, null, NOW);
    assertTrue(Capacity.hasPassedLimit(fresh, 1, 0, 0, 0, config, NOW));
  }

  @Test
  void aTeamIsMeasuredAgainstItsSeatsTimesTheMaximum() {
    Config config =
        config("STRIPE_SECRET_KEY", "sk", "MAX_LINKS_PER_USER", "10",
            "NEXT_PUBLIC_REQUIRE_CC", "true");
    Account team = new Account(null, 5, 3, "STRIPE", NOW.minusSeconds(40L * 86_400));
    assertFalse(Capacity.hasPassedLimit(team, 1, 5, 29, 0, config, NOW));
    assertTrue(Capacity.hasPassedLimit(team, 2, 5, 29, 0, config, NOW));
  }

  @Test
  void aSingleSeatIsMeasuredAgainstItsOwnLinksOnly() {
    Config config =
        config("STRIPE_SECRET_KEY", "sk", "MAX_LINKS_PER_USER", "10",
            "NEXT_PUBLIC_REQUIRE_CC", "true");
    Account single = new Account(null, 5, 1, "STRIPE", NOW.minusSeconds(40L * 86_400));
    assertFalse(Capacity.hasPassedLimit(single, 1, 5, 99, 0, config, NOW));
  }

  @Test
  void aMemberOfAnOversubscribedTeamIsRefused() {
    Config config =
        config("STRIPE_SECRET_KEY", "sk", "MAX_LINKS_PER_USER", "10",
            "NEXT_PUBLIC_REQUIRE_CC", "true");
    Account child = new Account(5, null, 2, "STRIPE", NOW.minusSeconds(40L * 86_400));
    assertTrue(Capacity.hasPassedLimit(child, 1, 0, 0, 2, config, NOW));
    assertFalse(Capacity.hasPassedLimit(child, 1, 0, 0, 1, config, NOW));
  }

  @Test
  void aStripeSubscriptionTakesTheBillingPathEvenWithTheKeyAbsent() {
    Config config = config("MAX_LINKS_PER_USER", "10");
    Instant expired = NOW.minusSeconds(40L * 86_400);
    // No quantity to measure against is the billing path's own refusal, and it is the only
    // way to tell that path apart from the per-user one on these numbers.
    assertTrue(
        Capacity.hasPassedLimit(
            new Account(null, 5, null, "STRIPE", expired), 1, 0, 0, 0, config, NOW));
    assertFalse(
        Capacity.hasPassedLimit(
            new Account(null, 5, null, null, expired), 1, 0, 0, 0, config, NOW));
  }
}

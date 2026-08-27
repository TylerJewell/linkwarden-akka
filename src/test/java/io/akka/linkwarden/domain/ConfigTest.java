package io.akka.linkwarden.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** SPEC-001 R1–R4: what the configuration route publishes, and how a setting is read. */
class ConfigTest {

  private static Config with(String... pairs) {
    Map<String, String> env = new java.util.LinkedHashMap<>();
    for (int i = 0; i < pairs.length; i += 2) env.put(pairs[i], pairs[i + 1]);
    return new Config(env);
  }

  @Test
  void anUnsetFlagIsPublishedAsNullRatherThanFalse() {
    Map<String, Object> published = with().published("v2.16.1");
    assertNull(published.get("DISABLE_REGISTRATION"));
    assertNull(published.get("DEMO"));
    assertNull(published.get("REQUIRE_CC"));
  }

  @Test
  void aFlagIsTrueOnlyForTheExactString() {
    assertTrue(with("NEXT_PUBLIC_DEMO", "true").flag("NEXT_PUBLIC_DEMO"));
    assertFalse(with("NEXT_PUBLIC_DEMO", "TRUE").flag("NEXT_PUBLIC_DEMO"));
    assertFalse(with("NEXT_PUBLIC_DEMO", "1").flag("NEXT_PUBLIC_DEMO"));
    assertFalse(with("NEXT_PUBLIC_DEMO", "").flag("NEXT_PUBLIC_DEMO"));
  }

  @Test
  void zeroAndUnparseableNumbersArePublishedAsNull() {
    assertNull(with("NEXT_PUBLIC_ADMIN", "0").publishedNumber("NEXT_PUBLIC_ADMIN"));
    assertNull(with("NEXT_PUBLIC_ADMIN", "abc").publishedNumber("NEXT_PUBLIC_ADMIN"));
    assertEquals(7, with("NEXT_PUBLIC_ADMIN", "7").publishedNumber("NEXT_PUBLIC_ADMIN"));
  }

  @Test
  void aZeroNumberFallsBackToItsDefaultWhenRead() {
    assertEquals(50, with("PAGINATION_TAKE_COUNT", "0").paginationTakeCount());
    assertEquals(20, with("PAGINATION_TAKE_COUNT", "20").paginationTakeCount());
    assertEquals(1, with().adminId());
  }

  @Test
  void demoCredentialsArePublishedOnlyInDemoMode() {
    Config off = with("NEXT_PUBLIC_DEMO_USERNAME", "guest");
    assertNull(off.published("v").get("DEMO_USERNAME"));
    Config on = with("NEXT_PUBLIC_DEMO", "true", "NEXT_PUBLIC_DEMO_USERNAME", "guest");
    assertEquals("guest", on.published("v").get("DEMO_USERNAME"));
  }

  @Test
  void billingIsPublishedFromEitherSetting() {
    assertNull(with().published("v").get("STRIPE_ENABLED"));
    assertEquals(
        Boolean.TRUE, with("NEXT_PUBLIC_STRIPE", "true").published("v").get("STRIPE_ENABLED"));
    assertEquals(
        Boolean.TRUE, with("STRIPE_SECRET_KEY", "sk_x").published("v").get("STRIPE_ENABLED"));
  }

  @Test
  void theConfigurationAnswerCarriesSeventeenKeysInOrder() {
    List<String> keys = List.copyOf(with().published("v2.16.1").keySet());
    assertEquals(17, keys.size());
    assertEquals("DISABLE_REGISTRATION", keys.get(0));
    assertEquals("MOBILE_APP_REDIRECT_ENABLED", keys.get(16));
  }

  @Test
  void theLoginsAnswerIsStringsAndCredentialsDefaultOn() {
    Map<String, Object> logins = with().logins();
    assertEquals("true", logins.get("credentialsEnabled"));
    assertEquals("false", logins.get("emailEnabled"));
    assertEquals("false", logins.get("registrationDisabled"));
    assertEquals(List.of(), logins.get("buttonAuths"));
  }

  @Test
  void credentialsAreOffOnlyForTheExactStringFalse() {
    assertEquals(
        "false", with("NEXT_PUBLIC_CREDENTIALS_ENABLED", "false").logins().get("credentialsEnabled"));
    assertEquals(
        "true", with("NEXT_PUBLIC_CREDENTIALS_ENABLED", "no").logins().get("credentialsEnabled"));
  }

  @Test
  void anEnabledProviderAppearsAsAButtonWithItsCustomName() {
    Config config =
        with("NEXT_PUBLIC_GOOGLE_ENABLED", "true", "GOOGLE_CUSTOM_NAME", "Company sign-in");
    @SuppressWarnings("unchecked")
    List<Map<String, String>> buttons = (List<Map<String, String>>) config.logins().get("buttonAuths");
    assertEquals(1, buttons.size());
    assertEquals("google", buttons.get(0).get("method"));
    assertEquals("Company sign-in", buttons.get(0).get("name"));
  }

  @Test
  void zoomIsEnabledByEitherOfItsTwoFlags() {
    assertEquals(
        1, ((List<?>) with("NEXT_PUBLIC_ZOOM_ENABLED", "true").logins().get("buttonAuths")).size());
    assertEquals(
        1,
        ((List<?>) with("NEXT_PUBLIC_ZOOM_ENABLED_ENABLED", "true").logins().get("buttonAuths"))
            .size());
  }

  @Test
  void everyProviderTheOriginalShipsIsPresent() {
    assertEquals(58, AuthProviders.ALL.size());
  }
}

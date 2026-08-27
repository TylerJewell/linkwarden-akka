package io.akka.linkwarden.domain;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Every setting the instance is configured with, and the answer the configuration route
 * publishes. SPEC-001 R1–R4.
 *
 * <p>The coercion is the rule, not a convenience: a boolean setting that is off reads as {@code
 * null} rather than {@code false}, and a number that is absent, unparseable or zero reads as
 * {@code null} too. A caller distinguishes "not configured" from "configured off" by neither, so
 * regularising it would change what the interface is told.
 */
public record Config(Map<String, String> env) {

  public static final int DEFAULT_PAGINATION = 50;
  public static final int DEFAULT_MAX_LINKS_PER_USER = 30000;
  public static final int DEFAULT_ARCHIVE_TAKE = 5;
  public static final int DEFAULT_INDEX_TAKE = 50;
  public static final int DEFAULT_RSS_POLL_MINUTES = 60;
  public static final int DEFAULT_RSS_LIMIT_PER_USER = 20;
  public static final int DEFAULT_IMPORT_LIMIT_MB = 10;
  public static final int DEFAULT_MAX_FILE_BUFFER_MB = 10;
  public static final int DEFAULT_WORKER_INTERVAL_SECONDS = 10;
  public static final int DEFAULT_ADMIN_ID = 1;
  public static final String DEFAULT_STORAGE_FOLDER = "data";
  public static final int DEFAULT_TRIAL_PERIOD_DAYS = 14;

  /**
   * The process environment, with any system property of the same name laid over it.
   *
   * <p>The overlay is what lets one run be configured differently from the one beside it without
   * a second process: whether an email provider is configured decides which rules exist at all,
   * and a check of those rules has to be able to say so for itself.
   */
  public static Config fromEnvironment() {
    Map<String, String> merged = new LinkedHashMap<>(System.getenv());
    for (String name : System.getProperties().stringPropertyNames()) {
      String value = System.getProperty(name);
      if (value != null) merged.put(name, value);
    }
    return new Config(Map.copyOf(merged));
  }

  public String raw(String key) {
    String value = env.get(key);
    return value == null || value.isEmpty() ? null : value;
  }

  /** True only for the exact string {@code true}. */
  public boolean flag(String key) {
    return "true".equals(env.get(key));
  }

  /** A flag as the configuration route publishes it: {@code true} or {@code null}. */
  public Boolean publishedFlag(String key) {
    return flag(key) ? Boolean.TRUE : null;
  }

  public int number(String key, int fallback) {
    String value = raw(key);
    if (value == null) return fallback;
    try {
      double parsed = Double.parseDouble(value.trim());
      return parsed == 0 ? fallback : (int) parsed;
    } catch (NumberFormatException e) {
      return fallback;
    }
  }

  /** A number as the configuration route publishes it: absent, unparseable and zero are null. */
  public Integer publishedNumber(String key) {
    String value = raw(key);
    if (value == null) return null;
    try {
      double parsed = Double.parseDouble(value.trim());
      return parsed == 0 ? null : (int) parsed;
    } catch (NumberFormatException e) {
      return null;
    }
  }

  public boolean emailEnabled() {
    return raw("EMAIL_FROM") != null && raw("EMAIL_SERVER") != null;
  }

  public boolean billingEnabled() {
    return raw("STRIPE_SECRET_KEY") != null;
  }

  public boolean demoMode() {
    return flag("NEXT_PUBLIC_DEMO");
  }

  public boolean aiProviderConfigured() {
    return raw("NEXT_PUBLIC_OLLAMA_ENDPOINT_URL") != null
        || raw("OPENAI_API_KEY") != null
        || raw("AZURE_API_KEY") != null
        || raw("ANTHROPIC_API_KEY") != null
        || raw("OPENROUTER_API_KEY") != null
        || raw("PERPLEXITY_API_KEY") != null;
  }

  public int adminId() {
    return number("NEXT_PUBLIC_ADMIN", DEFAULT_ADMIN_ID);
  }

  public int paginationTakeCount() {
    return number("PAGINATION_TAKE_COUNT", DEFAULT_PAGINATION);
  }

  public int maxLinksPerUser() {
    return number("MAX_LINKS_PER_USER", DEFAULT_MAX_LINKS_PER_USER);
  }

  public int rssSubscriptionLimitPerUser() {
    return number("RSS_SUBSCRIPTION_LIMIT_PER_USER", DEFAULT_RSS_LIMIT_PER_USER);
  }

  public int importLimitMb() {
    return number("IMPORT_LIMIT", DEFAULT_IMPORT_LIMIT_MB);
  }

  public int maxFileBufferMb() {
    return number("NEXT_PUBLIC_MAX_FILE_BUFFER", DEFAULT_MAX_FILE_BUFFER_MB);
  }

  public int searchFilterLimit() {
    return number("SEARCH_FILTER_LIMIT", 0);
  }

  public String storageFolder() {
    String folder = raw("STORAGE_FOLDER");
    return folder == null ? DEFAULT_STORAGE_FOLDER : folder;
  }

  public String userContentDomain() {
    return raw("NEXT_PUBLIC_USER_CONTENT_DOMAIN");
  }

  public boolean allowPrivateNetworkAccess() {
    return flag("ALLOW_PRIVATE_NETWORK_ACCESS");
  }

  /** The seventeen keys of {@code GET /api/v1/config}, in the order the original writes them. */
  public Map<String, Object> published(String instanceVersion) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("DISABLE_REGISTRATION", publishedFlag("NEXT_PUBLIC_DISABLE_REGISTRATION"));
    out.put("ADMIN", publishedNumber("NEXT_PUBLIC_ADMIN"));
    out.put(
        "RSS_POLLING_INTERVAL_MINUTES",
        publishedNumber("NEXT_PUBLIC_RSS_POLLING_INTERVAL_MINUTES"));
    out.put("EMAIL_PROVIDER", publishedFlag("NEXT_PUBLIC_EMAIL_PROVIDER"));
    out.put("MAX_FILE_BUFFER", publishedNumber("NEXT_PUBLIC_MAX_FILE_BUFFER"));
    out.put("USER_CONTENT_DOMAIN", raw("NEXT_PUBLIC_USER_CONTENT_DOMAIN"));
    out.put("AI_ENABLED", aiProviderConfigured() ? Boolean.TRUE : null);
    out.put("INSTANCE_VERSION", instanceVersion);
    out.put(
        "STRIPE_ENABLED",
        flag("NEXT_PUBLIC_STRIPE") || raw("STRIPE_SECRET_KEY") != null ? Boolean.TRUE : null);
    out.put("STRIPE_BILLING_PORTAL_URL", raw("NEXT_PUBLIC_STRIPE_BILLING_PORTAL_URL"));
    out.put("TRIAL_PERIOD_DAYS", publishedNumber("NEXT_PUBLIC_TRIAL_PERIOD_DAYS"));
    out.put("REQUIRE_CC", publishedFlag("NEXT_PUBLIC_REQUIRE_CC"));
    out.put("DEMO", publishedFlag("NEXT_PUBLIC_DEMO"));
    Function<String, Object> onlyInDemo = key -> demoMode() ? raw(key) : null;
    out.put("DEMO_USERNAME", onlyInDemo.apply("NEXT_PUBLIC_DEMO_USERNAME"));
    out.put("DEMO_PASSWORD", onlyInDemo.apply("NEXT_PUBLIC_DEMO_PASSWORD"));
    out.put("GOOGLE_ENABLED", publishedFlag("NEXT_PUBLIC_GOOGLE_ENABLED"));
    out.put(
        "MOBILE_APP_REDIRECT_ENABLED", publishedFlag("NEXT_PUBLIC_MOBILE_APP_REDIRECT_ENABLED"));
    return out;
  }

  /** The answer of {@code GET /api/v1/logins}: three strings and the provider buttons. */
  public Map<String, Object> logins() {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put(
        "credentialsEnabled",
        "false".equals(env.get("NEXT_PUBLIC_CREDENTIALS_ENABLED")) ? "false" : "true");
    out.put("emailEnabled", flag("NEXT_PUBLIC_EMAIL_PROVIDER") ? "true" : "false");
    out.put("registrationDisabled", flag("NEXT_PUBLIC_DISABLE_REGISTRATION") ? "true" : "false");
    out.put("buttonAuths", AuthProviders.enabled(this));
    return out;
  }
}

package io.akka.linkwarden.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The fifty-eight single-sign-on providers a Linkwarden instance can be configured with, and the
 * two rules that read them. SPEC-001 R3.
 *
 * <p>A provider is enabled when <em>any</em> of its flag variables reads the exact string {@code
 * true}, and its button carries the custom name when one is set and its default otherwise. Zoom
 * has two flag variables because a doubled name shipped once and instances set it.
 */
public final class AuthProviders {

  public record Entry(String id, String defaultName, List<String> flagEnvs, String customNameEnv) {}

  public static final List<Entry> ALL =
      List.of(
          new Entry("42-school", "42 School", List.of("NEXT_PUBLIC_FORTYTWO_ENABLED"), "FORTYTWO_CUSTOM_NAME"),
          new Entry("apple", "Apple", List.of("NEXT_PUBLIC_APPLE_ENABLED"), "APPLE_CUSTOM_NAME"),
          new Entry("atlassian", "Atlassian", List.of("NEXT_PUBLIC_ATLASSIAN_ENABLED"), "ATLASSIAN_CUSTOM_NAME"),
          new Entry("auth0", "Auth0", List.of("NEXT_PUBLIC_AUTH0_ENABLED"), "AUTH0_CUSTOM_NAME"),
          new Entry("authentik", "Authentik", List.of("NEXT_PUBLIC_AUTHENTIK_ENABLED"), "AUTHENTIK_CUSTOM_NAME"),
          new Entry("azure-ad-b2c", "Azure AD B2C", List.of("NEXT_PUBLIC_AZURE_AD_B2C_ENABLED"), "AZURE_AD_B2C_CUSTOM_NAME"),
          new Entry("azure-ad", "Azure AD", List.of("NEXT_PUBLIC_AZURE_AD_ENABLED"), "AZURE_AD_CUSTOM_NAME"),
          new Entry("battlenet", "Battle.net", List.of("NEXT_PUBLIC_BATTLENET_ENABLED"), "BATTLENET_CUSTOM_NAME"),
          new Entry("box", "Box", List.of("NEXT_PUBLIC_BOX_ENABLED"), "BOX_CUSTOM_NAME"),
          new Entry("cognito", "Cognito", List.of("NEXT_PUBLIC_COGNITO_ENABLED"), "COGNITO_CUSTOM_NAME"),
          new Entry("coinbase", "Coinbase", List.of("NEXT_PUBLIC_COINBASE_ENABLED"), "COINBASE_CUSTOM_NAME"),
          new Entry("discord", "Discord", List.of("NEXT_PUBLIC_DISCORD_ENABLED"), "DISCORD_CUSTOM_NAME"),
          new Entry("dropbox", "Dropbox", List.of("NEXT_PUBLIC_DROPBOX_ENABLED"), "DROPBOX_CUSTOM_NAME"),
          new Entry("duende-identityserver6", "DuendeIdentityServer6", List.of("NEXT_PUBLIC_DUENDE_IDS6_ENABLED"), "DUENDE_IDS6_CUSTOM_NAME"),
          new Entry("eveonline", "EVE Online", List.of("NEXT_PUBLIC_EVEONLINE_ENABLED"), "EVEONLINE_CUSTOM_NAME"),
          new Entry("facebook", "Facebook", List.of("NEXT_PUBLIC_FACEBOOK_ENABLED"), "FACEBOOK_CUSTOM_NAME"),
          new Entry("faceit", "FACEIT", List.of("NEXT_PUBLIC_FACEIT_ENABLED"), "FACEIT_CUSTOM_NAME"),
          new Entry("foursquare", "Foursquare", List.of("NEXT_PUBLIC_FOURSQUARE_ENABLED"), "FOURSQUARE_CUSTOM_NAME"),
          new Entry("freshbooks", "Freshbooks", List.of("NEXT_PUBLIC_FRESHBOOKS_ENABLED"), "FRESHBOOKS_CUSTOM_NAME"),
          new Entry("fusionauth", "FusionAuth", List.of("NEXT_PUBLIC_FUSIONAUTH_ENABLED"), "FUSIONAUTH_CUSTOM_NAME"),
          new Entry("github", "GitHub", List.of("NEXT_PUBLIC_GITHUB_ENABLED"), "GITHUB_CUSTOM_NAME"),
          new Entry("gitlab", "GitLab", List.of("NEXT_PUBLIC_GITLAB_ENABLED"), "GITLAB_CUSTOM_NAME"),
          new Entry("google", "Google", List.of("NEXT_PUBLIC_GOOGLE_ENABLED"), "GOOGLE_CUSTOM_NAME"),
          new Entry("hubspot", "HubSpot", List.of("NEXT_PUBLIC_HUBSPOT_ENABLED"), "HUBSPOT_CUSTOM_NAME"),
          new Entry("identity-server4", "IdentityServer4", List.of("NEXT_PUBLIC_IDS4_ENABLED"), "IDS4_CUSTOM_NAME"),
          new Entry("kakao", "Kakao", List.of("NEXT_PUBLIC_KAKAO_ENABLED"), "KAKAO_CUSTOM_NAME"),
          new Entry("keycloak", "Keycloak", List.of("NEXT_PUBLIC_KEYCLOAK_ENABLED"), "KEYCLOAK_CUSTOM_NAME"),
          new Entry("line", "LINE", List.of("NEXT_PUBLIC_LINE_ENABLED"), "LINE_CUSTOM_NAME"),
          new Entry("linkedin", "LinkedIn", List.of("NEXT_PUBLIC_LINKEDIN_ENABLED"), "LINKEDIN_CUSTOM_NAME"),
          new Entry("mailchimp", "Mailchimp", List.of("NEXT_PUBLIC_MAILCHIMP_ENABLED"), "MAILCHIMP_CUSTOM_NAME"),
          new Entry("mailru", "Mail.ru", List.of("NEXT_PUBLIC_MAILRU_ENABLED"), "MAILRU_CUSTOM_NAME"),
          new Entry("naver", "Naver", List.of("NEXT_PUBLIC_NAVER_ENABLED"), "NAVER_CUSTOM_NAME"),
          new Entry("netlify", "Netlify", List.of("NEXT_PUBLIC_NETLIFY_ENABLED"), "NETLIFY_CUSTOM_NAME"),
          new Entry("okta", "Okta", List.of("NEXT_PUBLIC_OKTA_ENABLED"), "OKTA_CUSTOM_NAME"),
          new Entry("onelogin", "OneLogin", List.of("NEXT_PUBLIC_ONELOGIN_ENABLED"), "ONELOGIN_CUSTOM_NAME"),
          new Entry("osso", "Osso", List.of("NEXT_PUBLIC_OSSO_ENABLED"), "OSSO_CUSTOM_NAME"),
          new Entry("osu", "Osu!", List.of("NEXT_PUBLIC_OSU_ENABLED"), "OSU_CUSTOM_NAME"),
          new Entry("patreon", "Patreon", List.of("NEXT_PUBLIC_PATREON_ENABLED"), "PATREON_CUSTOM_NAME"),
          new Entry("pinterest", "Pinterest", List.of("NEXT_PUBLIC_PINTEREST_ENABLED"), "PINTEREST_CUSTOM_NAME"),
          new Entry("pipedrive", "Pipedrive", List.of("NEXT_PUBLIC_PIPEDRIVE_ENABLED"), "PIPEDRIVE_CUSTOM_NAME"),
          new Entry("reddit", "Reddit", List.of("NEXT_PUBLIC_REDDIT_ENABLED"), "REDDIT_CUSTOM_NAME"),
          new Entry("salesforce", "Salesforce", List.of("NEXT_PUBLIC_SALESFORCE_ENABLED"), "SALESFORCE_CUSTOM_NAME"),
          new Entry("slack", "Slack", List.of("NEXT_PUBLIC_SLACK_ENABLED"), "SLACK_CUSTOM_NAME"),
          new Entry("spotify", "Spotify", List.of("NEXT_PUBLIC_SPOTIFY_ENABLED"), "SPOTIFY_CUSTOM_NAME"),
          new Entry("strava", "Strava", List.of("NEXT_PUBLIC_STRAVA_ENABLED"), "STRAVA_CUSTOM_NAME"),
          new Entry("synology", "Synology", List.of("NEXT_PUBLIC_SYNOLOGY_ENABLED"), "SYNOLOGY_CUSTOM_NAME"),
          new Entry("todoist", "Todoist", List.of("NEXT_PUBLIC_TODOIST_ENABLED"), "TODOIST_CUSTOM_NAME"),
          new Entry("twitch", "Twitch", List.of("NEXT_PUBLIC_TWITCH_ENABLED"), "TWITCH_CUSTOM_NAME"),
          new Entry("united-effects", "United Effects", List.of("NEXT_PUBLIC_UNITED_EFFECTS_ENABLED"), "UNITED_EFFECTS_CUSTOM_NAME"),
          new Entry("vk", "VK", List.of("NEXT_PUBLIC_VK_ENABLED"), "VK_CUSTOM_NAME"),
          new Entry("wikimedia", "Wikimedia", List.of("NEXT_PUBLIC_WIKIMEDIA_ENABLED"), "WIKIMEDIA_CUSTOM_NAME"),
          new Entry("wordpress", "WordPress.com", List.of("NEXT_PUBLIC_WORDPRESS_ENABLED"), "WORDPRESS_CUSTOM_NAME"),
          new Entry("yandex", "Yandex", List.of("NEXT_PUBLIC_YANDEX_ENABLED"), "YANDEX_CUSTOM_NAME"),
          new Entry("zitadel", "ZITADEL", List.of("NEXT_PUBLIC_ZITADEL_ENABLED"), "ZITADEL_CUSTOM_NAME"),
          new Entry("zoho", "Zoho", List.of("NEXT_PUBLIC_ZOHO_ENABLED"), "ZOHO_CUSTOM_NAME"),
          new Entry("zoom", "Zoom", List.of("NEXT_PUBLIC_ZOOM_ENABLED", "NEXT_PUBLIC_ZOOM_ENABLED_ENABLED"), "ZOOM_CUSTOM_NAME"),
          new Entry("authelia", "Authelia", List.of("NEXT_PUBLIC_AUTHELIA_ENABLED"), "AUTHELIA_CUSTOM_NAME"),
          new Entry("oidc", "OIDC", List.of("NEXT_PUBLIC_OIDC_ENABLED"), "OIDC_CUSTOM_NAME"));

  private AuthProviders() {}

  public static boolean isEnabled(Entry entry, Config config) {
    return entry.flagEnvs().stream().anyMatch(config::flag);
  }

  public static String buttonName(Entry entry, Config config) {
    String custom = config.raw(entry.customNameEnv());
    return custom == null ? entry.defaultName() : custom;
  }

  public static List<Map<String, String>> enabled(Config config) {
    List<Map<String, String>> out = new ArrayList<>();
    for (Entry entry : ALL) {
      if (!isEnabled(entry, config)) continue;
      Map<String, String> button = new LinkedHashMap<>();
      button.put("method", entry.id());
      button.put("name", buttonName(entry, config));
      out.add(button);
    }
    return out;
  }
}

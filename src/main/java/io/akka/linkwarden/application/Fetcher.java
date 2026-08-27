package io.akka.linkwarden.application;

import io.akka.linkwarden.domain.Config;
import io.akka.linkwarden.domain.MetaDescription;
import io.akka.linkwarden.domain.PageFacts;
import io.akka.linkwarden.domain.Ssrf;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The one place the server fetches something a caller named. SPEC-001 R37–R38, R96.
 *
 * <p>Redirects are followed by hand rather than by the client, because the guard has to be asked
 * again at each hop: a name that resolves publicly can redirect to one that does not, and a client
 * that follows redirects itself never offers the intermediate address to be checked.
 */
public final class Fetcher {

  /** What a fetch answered, as the two rules that read it need it. */
  public record Page(String title, String contentType) {

    public static final Page NOTHING = new Page("", null);
  }

  private static final Pattern TITLE = Pattern.compile("<title.*?>([^<]*)</title>", Pattern.DOTALL);
  private static final int MAX_REDIRECTS = 5;
  private static final Duration TIMEOUT = Duration.ofSeconds(10);

  private final Config config;
  private final HttpClient client;

  public Fetcher(Config config) {
    this.config = config;
    this.client =
        HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(TIMEOUT)
            .build();
  }

  public boolean isSafe(String url) {
    return url != null && Ssrf.isSafe(url, config, Ssrf.SYSTEM_LOOKUP);
  }

  /**
   * The page's title and content type, or nothing at all.
   *
   * <p>A failure is not an error a caller sees: the original races the fetch against a ten-second
   * timeout and carries on with an empty title when it loses, so a link is saved either way.
   */
  public Page titleAndHeaders(String url) {
    if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) {
      return Page.NOTHING;
    }
    try {
      String current = url;
      for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
        URI safe = Ssrf.assertSafe(current, config, Ssrf.SYSTEM_LOOKUP);
        HttpRequest request =
            HttpRequest.newBuilder(safe)
                .timeout(TIMEOUT)
                .header("User-Agent", "Linkwarden (Server-Side Fetch)")
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 300 && response.statusCode() < 400) {
          Optional<String> location = response.headers().firstValue("location");
          if (location.isEmpty()) return read(response);
          current = safe.resolve(location.get()).toString();
          continue;
        }
        return read(response);
      }
      return Page.NOTHING;
    } catch (Exception e) {
      return Page.NOTHING;
    }
  }

  /**
   * What one preservation attempt is told about a page. SPEC-001 §5 B.
   *
   * <p>Three of these fields are a renderer's answers and this instance has no renderer, so they
   * come back as the absence they would have on a failed render: the extractor's text is not
   * produced, and the byte counts are zero. The rest are read from the response itself, which is
   * what decides which steps the plan runs at all.
   */
  public PageFacts facts(String url) {
    if (url == null) return new PageFacts(null, null, null, null, null, 0, 0, 0, true, true, false, false);
    if (!isSafe(url)) {
      return new PageFacts(null, null, null, null, null, 0, 0, 0, false, false, false, true);
    }
    Body body = body(url);
    if (body == null) {
      return new PageFacts(null, null, null, null, null, 0, 0, 0, true, true, false, false);
    }
    String origin = null;
    try {
      URI uri = URI.create(url);
      origin = uri.getScheme() + "://" + uri.getHost();
    } catch (RuntimeException ignored) {
      // A url that will not parse has no origin; the preview rule then has nothing to resolve
      // a relative image against, which is the same position a page with no image is in.
    }
    org.jsoup.nodes.Document document = org.jsoup.Jsoup.parse(body.text() == null ? "" : body.text());
    String description = attribute(document, "meta[name=description]", "content");
    String ogImage = attribute(document, "meta[property=og:image]", "content");
    return new PageFacts(
        body.contentType(),
        ogImage,
        origin,
        MetaDescription.clip(description),
        null,
        0,
        0,
        0,
        false,
        true,
        false,
        false);
  }

  private static String attribute(org.jsoup.nodes.Document document, String selector, String name) {
    org.jsoup.nodes.Element element = document.selectFirst(selector);
    if (element == null) return null;
    String value = element.attr(name);
    return value == null || value.isEmpty() ? null : value;
  }

  /** A fetched response, kept whole because the facts read both its headers and its body. */
  private record Body(String contentType, String text) {}

  private Body body(String url) {
    if (!(url.startsWith("http://") || url.startsWith("https://"))) return null;
    try {
      String current = url;
      for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
        URI safe = Ssrf.assertSafe(current, config, Ssrf.SYSTEM_LOOKUP);
        HttpRequest request =
            HttpRequest.newBuilder(safe)
                .timeout(TIMEOUT)
                .header("User-Agent", "Linkwarden (Server-Side Fetch)")
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 300 && response.statusCode() < 400) {
          Optional<String> location = response.headers().firstValue("location");
          if (location.isEmpty()) return new Body(contentTypeOf(response), response.body());
          current = safe.resolve(location.get()).toString();
          continue;
        }
        return new Body(contentTypeOf(response), response.body());
      }
      return null;
    } catch (Exception e) {
      return null;
    }
  }

  private static String contentTypeOf(HttpResponse<String> response) {
    String contentType = response.headers().firstValue("content-type").orElse(null);
    if (contentType != null) {
      int semicolon = contentType.indexOf(';');
      if (semicolon > 0) contentType = contentType.substring(0, semicolon).trim();
    }
    return contentType;
  }

  private static Page read(HttpResponse<String> response) {
    String contentType = response.headers().firstValue("content-type").orElse(null);
    if (contentType != null) {
      int semicolon = contentType.indexOf(';');
      if (semicolon > 0) contentType = contentType.substring(0, semicolon).trim();
    }
    Matcher matcher = TITLE.matcher(response.body() == null ? "" : response.body());
    String title = matcher.find() ? matcher.group(1) : "";
    return new Page(title, contentType);
  }
}

package io.akka.linkwarden.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * SPEC-001 R91–R96: the guard in front of every fetch made on a caller's behalf.
 *
 * <p>The name resolver is supplied by each case, so the rule is driven over addresses that cannot
 * be reached from here and the answers do not depend on what a name server happens to say today.
 */
class SsrfTest {

  private static final Config OPEN = new Config(Map.of());

  private static java.util.function.Function<String, List<String>> resolvesTo(String... addresses) {
    return hostname -> List.of(addresses);
  }

  @Test
  void onlyHttpAndHttpsAreAccepted() {
    Ssrf.UnsafeUrlException refused =
        assertThrows(
            Ssrf.UnsafeUrlException.class,
            () -> Ssrf.assertSafe("file:///etc/passwd", OPEN, resolvesTo("93.184.216.34")));
    assertEquals("Only http:// and https:// URLs can be archived.", refused.getMessage());
  }

  @Test
  void theFiveNamedHostnamesAreBlocked() {
    for (String hostname :
        List.of(
            "localhost",
            "localhost.localdomain",
            "ip6-localhost",
            "ip6-loopback",
            "broadcasthost")) {
      assertTrue(Ssrf.isHostnameBlocked(hostname), hostname);
    }
  }

  @Test
  void theFourSuffixesAreBlocked() {
    for (String hostname :
        List.of("app.localhost", "printer.local", "box.localdomain", "api.internal")) {
      assertTrue(Ssrf.isHostnameBlocked(hostname), hostname);
    }
  }

  @Test
  void aDotlessNameThatIsNotAnAddressIsBlocked() {
    assertTrue(Ssrf.isHostnameBlocked("intranet"));
    assertFalse(Ssrf.isHostnameBlocked("example.com"));
    assertFalse(Ssrf.isHostnameBlocked("93.184.216.34"));
  }

  @Test
  void aHostnameIsComparedTrimmedLowercasedAndWithoutTrailingDots() {
    assertTrue(Ssrf.isHostnameBlocked("  LOCALHOST.  "));
    assertEquals("example.com", Ssrf.normaliseHostname("  Example.COM..  "));
  }

  @Test
  void everyBlockedFourthVersionRangeIsRefused() {
    for (String address :
        List.of(
            "0.1.2.3",
            "10.0.0.1",
            "100.64.0.1",
            "127.0.0.1",
            "169.254.169.254",
            "172.16.0.1",
            "192.0.0.1",
            "192.0.2.1",
            "192.168.1.1",
            "198.18.0.1",
            "198.51.100.1",
            "203.0.113.1",
            "224.0.0.1",
            "240.0.0.1")) {
      assertTrue(Ssrf.isAddressBlocked(address), address);
    }
  }

  @Test
  void anOrdinaryPublicAddressIsAllowed() {
    assertFalse(Ssrf.isAddressBlocked("93.184.216.34"));
    assertFalse(Ssrf.isAddressBlocked("8.8.8.8"));
    assertFalse(Ssrf.isAddressBlocked("2606:2800:220:1:248:1893:25c8:1946"));
  }

  @Test
  void everyBlockedSixthVersionRangeIsRefused() {
    for (String address : List.of("::", "::1", "fc00::1", "fe80::1", "ff02::1", "2001:db8::1")) {
      assertTrue(Ssrf.isAddressBlocked(address), address);
    }
  }

  @Test
  void anAddressEmbeddedInASixthVersionOneIsReadAsTheFourthVersion() {
    assertTrue(Ssrf.isAddressBlocked("::ffff:127.0.0.1"));
    assertFalse(Ssrf.isAddressBlocked("::ffff:93.184.216.34"));
  }

  @Test
  void somethingThatIsNeitherKindOfAddressIsRefused() {
    assertTrue(Ssrf.isAddressBlocked("not-an-address"));
    assertTrue(Ssrf.isAddressBlocked("999.1.1.1"));
  }

  @Test
  void aNameResolvingToAnyBlockedAddressIsRefused() {
    Ssrf.UnsafeUrlException refused =
        assertThrows(
            Ssrf.UnsafeUrlException.class,
            () ->
                Ssrf.assertSafe(
                    "https://sneaky.example/", OPEN, resolvesTo("93.184.216.34", "127.0.0.1")));
    assertEquals("URL resolves to a blocked internal IP address.", refused.getMessage());
  }

  @Test
  void aNameResolvingToNothingIsRefused() {
    Ssrf.UnsafeUrlException refused =
        assertThrows(
            Ssrf.UnsafeUrlException.class,
            () -> Ssrf.assertSafe("https://empty.example/", OPEN, hostname -> List.of()));
    assertEquals("URL hostname did not resolve to a public IP.", refused.getMessage());
  }

  @Test
  void aNameThatDoesNotExistHasItsOwnSentence() {
    Ssrf.UnsafeUrlException refused =
        assertThrows(
            Ssrf.UnsafeUrlException.class,
            () ->
                Ssrf.assertSafe(
                    "https://gone.example/",
                    OPEN,
                    hostname -> {
                      throw new Ssrf.UnknownHost();
                    }));
    assertEquals("URL hostname could not be resolved.", refused.getMessage());
  }

  @Test
  void anyOtherResolutionFailureHasTheOtherSentence() {
    Ssrf.UnsafeUrlException refused =
        assertThrows(
            Ssrf.UnsafeUrlException.class,
            () ->
                Ssrf.assertSafe(
                    "https://broken.example/",
                    OPEN,
                    hostname -> {
                      throw new IllegalStateException("resolver is down");
                    }));
    assertEquals("URL hostname lookup failed.", refused.getMessage());
  }

  @Test
  void theWholeGuardIsSkippedWhenPrivateAccessIsAllowed() {
    Config allowed = new Config(Map.of("ALLOW_PRIVATE_NETWORK_ACCESS", "true"));
    assertTrue(
        Ssrf.isSafe(
            "http://127.0.0.1:8080/feed",
            allowed,
            hostname -> {
              throw new AssertionError("the resolver must not be reached");
            }));
  }

  @Test
  void isSafeAnswersFalseRatherThanRaising() {
    assertFalse(Ssrf.isSafe("http://localhost/x", OPEN, resolvesTo("127.0.0.1")));
    assertTrue(Ssrf.isSafe("https://example.com/x", OPEN, resolvesTo("93.184.216.34")));
  }
}

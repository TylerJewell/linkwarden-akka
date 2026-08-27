package io.akka.linkwarden.domain;

import java.net.InetAddress;
import java.net.URI;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * The guard that stands in front of every fetch the server makes on a caller's behalf. SPEC-001
 * R91–R96.
 *
 * <p>The blocked ranges are the rule and are written out rather than derived, because a range that
 * is nearly right is indistinguishable from one that is right until somebody reaches an address
 * inside it.
 */
public final class Ssrf {

  /** Refused, with the sentence the caller is given. */
  public static final class UnsafeUrlException extends RuntimeException {
    public UnsafeUrlException(String message) {
      super(message);
    }
  }

  private static final Set<String> BLOCKED_HOSTNAMES =
      Set.of(
          "localhost",
          "localhost.localdomain",
          "ip6-localhost",
          "ip6-loopback",
          "broadcasthost");

  private static final List<String> BLOCKED_SUFFIXES =
      List.of(".localhost", ".local", ".localdomain", ".internal");

  private record V4Range(long network, long mask) {}

  private record V6Range(int[] network, int prefix) {}

  private static final List<V4Range> BLOCKED_V4 =
      List.of(
          v4("0.0.0.0", 8),
          v4("10.0.0.0", 8),
          v4("100.64.0.0", 10),
          v4("127.0.0.0", 8),
          v4("169.254.0.0", 16),
          v4("172.16.0.0", 12),
          v4("192.0.0.0", 24),
          v4("192.0.2.0", 24),
          v4("192.168.0.0", 16),
          v4("198.18.0.0", 15),
          v4("198.51.100.0", 24),
          v4("203.0.113.0", 24),
          v4("224.0.0.0", 4),
          v4("240.0.0.0", 4));

  private static final List<V6Range> BLOCKED_V6 =
      List.of(
          v6("::", 128),
          v6("::1", 128),
          v6("fc00::", 7),
          v6("fe80::", 10),
          v6("ff00::", 8),
          v6("2001:db8::", 32));

  private Ssrf() {}

  private static V4Range v4(String address, int prefix) {
    Long parsed = parseIPv4(address);
    if (parsed == null) throw new IllegalStateException("bad blocked range " + address);
    return new V4Range(parsed, maskV4(prefix));
  }

  private static V6Range v6(String address, int prefix) {
    int[] parsed = parseIPv6(address);
    if (parsed == null) throw new IllegalStateException("bad blocked range " + address);
    return new V6Range(parsed, prefix);
  }

  private static long maskV4(int prefix) {
    if (prefix == 0) return 0L;
    return (long) Math.floor(Math.pow(2, 32) - Math.pow(2, 32 - prefix));
  }

  public static String normaliseHostname(String hostname) {
    return hostname.trim().toLowerCase().replaceAll("\\.+$", "");
  }

  public static boolean isHostnameBlocked(String hostname) {
    String normalised = normaliseHostname(hostname);
    if (normalised.isEmpty()) return true;
    if (BLOCKED_HOSTNAMES.contains(normalised)) return true;
    if (BLOCKED_SUFFIXES.stream().anyMatch(normalised::endsWith)) return true;
    return !normalised.contains(".") && ipFamily(normalised) == 0;
  }

  public static boolean isAddressBlocked(String address) {
    Long ipv4 = parseIPv4(address);
    if (ipv4 == null) {
      String mapped = extractIPv4FromMappedIPv6(address);
      if (mapped != null) ipv4 = parseIPv4(mapped);
    }
    if (ipv4 != null) {
      long value = ipv4;
      return BLOCKED_V4.stream().anyMatch(r -> (value & r.mask()) == (r.network() & r.mask()));
    }
    int[] ipv6 = parseIPv6(address);
    if (ipv6 == null) return true;
    return BLOCKED_V6.stream().anyMatch(r -> matchesPrefix(ipv6, r.network(), r.prefix()));
  }

  /**
   * Resolves a hostname and refuses it when any address it answers with is blocked.
   *
   * <p>The lookup is a parameter so a test can drive the rule without a name server: the resolver
   * is a thing the claim is not about, which is what a stand-in may replace.
   */
  public static List<String> resolveOrThrow(
      String hostname, Function<String, List<String>> lookup) {
    String normalised = normaliseHostname(hostname);
    if (isHostnameBlocked(normalised)) {
      throw new UnsafeUrlException("URL resolves to a blocked internal hostname.");
    }
    int family = ipFamily(normalised);
    if (family == 4 || family == 6) {
      if (isAddressBlocked(normalised)) {
        throw new UnsafeUrlException("URL resolves to a blocked internal IP address.");
      }
      return List.of(normalised);
    }
    List<String> addresses;
    try {
      addresses = lookup.apply(normalised);
    } catch (UnknownHost e) {
      throw new UnsafeUrlException("URL hostname could not be resolved.");
    } catch (RuntimeException e) {
      throw new UnsafeUrlException("URL hostname lookup failed.");
    }
    if (addresses == null || addresses.isEmpty()) {
      throw new UnsafeUrlException("URL hostname did not resolve to a public IP.");
    }
    if (addresses.stream().anyMatch(Ssrf::isAddressBlocked)) {
      throw new UnsafeUrlException("URL resolves to a blocked internal IP address.");
    }
    return addresses;
  }

  /** A name that does not exist, kept apart because it has its own sentence. */
  public static final class UnknownHost extends RuntimeException {}

  public static final Function<String, List<String>> SYSTEM_LOOKUP =
      hostname -> {
        try {
          InetAddress[] found = InetAddress.getAllByName(hostname);
          return java.util.Arrays.stream(found).map(InetAddress::getHostAddress).toList();
        } catch (java.net.UnknownHostException e) {
          throw new UnknownHost();
        }
      };

  public static URI assertSafe(String value, Config config, Function<String, List<String>> lookup) {
    URI url;
    try {
      url = new URI(value);
    } catch (Exception e) {
      throw new UnsafeUrlException("Invalid URL");
    }
    String scheme = url.getScheme();
    if (!"http".equals(scheme) && !"https".equals(scheme)) {
      throw new UnsafeUrlException("Only http:// and https:// URLs can be archived.");
    }
    if (config.allowPrivateNetworkAccess()) return url;
    String host = url.getHost();
    resolveOrThrow(host == null ? "" : host, lookup);
    return url;
  }

  public static boolean isSafe(String value, Config config, Function<String, List<String>> lookup) {
    try {
      assertSafe(value, config, lookup);
      return true;
    } catch (UnsafeUrlException e) {
      return false;
    }
  }

  // ------------------------------------------------------------------
  // address parsing
  // ------------------------------------------------------------------

  /** 4, 6 or 0 — the same three answers the original's own check gives. */
  public static int ipFamily(String value) {
    if (parseIPv4(value) != null) return 4;
    return parseIPv6(value) != null ? 6 : 0;
  }

  static Long parseIPv4(String address) {
    String[] octets = address.split("\\.", -1);
    if (octets.length != 4) return null;
    long value = 0;
    for (String octet : octets) {
      if (!octet.matches("\\d{1,3}")) return null;
      int parsed = Integer.parseInt(octet);
      if (parsed > 255) return null;
      value = value * 256 + parsed;
    }
    return value;
  }

  static String extractIPv4FromMappedIPv6(String address) {
    String normalised = address.toLowerCase();
    if (!normalised.contains(".")) return null;
    String candidate = normalised.substring(normalised.lastIndexOf(':') + 1);
    return parseIPv4(candidate) == null ? null : candidate;
  }

  static int[] parseIPv6(String address) {
    String normalised = address.toLowerCase().split("%")[0];
    if (normalised.isEmpty()) return null;
    boolean doubleColon = normalised.contains("::");
    if (doubleColon && normalised.indexOf("::") != normalised.lastIndexOf("::")) return null;

    String leftRaw = doubleColon ? normalised.substring(0, normalised.indexOf("::")) : normalised;
    String rightRaw = doubleColon ? normalised.substring(normalised.indexOf("::") + 2) : "";

    List<String> left = expandSide(leftRaw);
    List<String> right = expandSide(rightRaw);
    if (left == null || right == null) return null;

    List<String> segments;
    if (doubleColon) {
      int zeroes = 8 - (left.size() + right.size());
      if (zeroes < 1) return null;
      segments = new java.util.ArrayList<>(left);
      for (int i = 0; i < zeroes; i++) segments.add("0");
      segments.addAll(right);
    } else {
      segments = left;
    }
    if (segments.size() != 8) return null;

    int[] parsed = new int[8];
    for (int i = 0; i < 8; i++) {
      if (!segments.get(i).matches("[0-9a-f]{1,4}")) return null;
      parsed[i] = Integer.parseInt(segments.get(i), 16);
    }
    return parsed;
  }

  private static List<String> expandSide(String side) {
    if (side.isEmpty()) return List.of();
    List<String> out = new java.util.ArrayList<>();
    for (String segment : side.split(":", -1)) {
      if (segment.isEmpty()) {
        out.add(segment);
      } else if (segment.contains(".")) {
        Long parsed = parseIPv4(segment);
        if (parsed == null) {
          out.add(segment);
        } else {
          out.add(String.format("%04x", parsed / 65536));
          out.add(String.format("%04x", parsed % 65536));
        }
      } else {
        out.add(segment);
      }
    }
    return out;
  }

  static boolean matchesPrefix(int[] address, int[] network, int prefixLength) {
    int fullSegments = prefixLength / 16;
    int remainingBits = prefixLength % 16;
    for (int i = 0; i < fullSegments; i++) {
      if (address[i] != network[i]) return false;
    }
    if (remainingBits == 0) return true;
    int mask = (0xffff << (16 - remainingBits)) & 0xffff;
    return (address[fullSegments] & mask) == (network[fullSegments] & mask);
  }
}

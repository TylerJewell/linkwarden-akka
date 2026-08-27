package io.akka.linkwarden.domain;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.ParseException;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

/**
 * The tokens a session and an API key are carried in. SPEC-001 R10, R12–R14, R87.
 *
 * <p>Three things are on a token and every rule reads one of them: the account it names, the
 * instant it stops being valid, and the identifier a revocation is recorded against. The token
 * itself is disclosed once and stored nowhere; what is stored is the identifier.
 */
public final class Tokens {

  public record Claims(int userId, String jti, Instant issuedAt, Instant expires, String scope) {}

  /** The claims a preserved-format link carries beyond the three above. SPEC-001 R87. */
  public record PreservedClaims(
      int linkId, String filePath, int format, Instant issuedAt, Instant expires) {}

  public static final String PRESERVED_FORMAT_SCOPE = "preserved-format";
  public static final long PRESERVED_FORMAT_TTL_SECONDS = 300;

  private Tokens() {}

  /**
   * The signing key.
   *
   * <p>The configured secret is hashed to thirty-two bytes rather than used directly, because the
   * signer refuses a key shorter than the digest it produces and a deployment's secret is whatever
   * somebody typed.
   */
  static byte[] key(String secret) {
    // An unset secret is a deployment that cannot mint anything, and saying so here is the
    // difference between one sentence and a stack trace about a digest that is fine.
    if (secret == null || secret.isBlank()) {
      throw new IllegalStateException("NEXTAUTH_SECRET is not configured.");
    }
    try {
      return MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is not available", e);
    }
  }

  public static String mint(String secret, int userId, Instant issuedAt, Instant expires) {
    return mint(secret, userId, UUID.randomUUID().toString(), issuedAt, expires, null);
  }

  public static String mint(
      String secret, int userId, String jti, Instant issuedAt, Instant expires, String scope) {
    JWTClaimsSet.Builder claims =
        new JWTClaimsSet.Builder()
            .claim("id", userId)
            .jwtID(jti)
            .issueTime(Date.from(issuedAt))
            .expirationTime(Date.from(expires));
    if (scope != null) claims.claim("scope", scope);
    return sign(secret, claims.build());
  }

  public static String mintPreserved(
      String secret, int linkId, String filePath, int format, Instant issuedAt) {
    Instant expires = issuedAt.plusSeconds(PRESERVED_FORMAT_TTL_SECONDS);
    JWTClaimsSet claims =
        new JWTClaimsSet.Builder()
            .claim("id", 0)
            .claim("scope", PRESERVED_FORMAT_SCOPE)
            .claim("linkId", linkId)
            .claim("filePath", filePath)
            .claim("format", format)
            .jwtID(UUID.randomUUID().toString())
            .issueTime(Date.from(issuedAt))
            .expirationTime(Date.from(expires))
            .build();
    return sign(secret, claims);
  }

  private static String sign(String secret, JWTClaimsSet claims) {
    try {
      SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
      jwt.sign(new MACSigner(key(secret)));
      return jwt.serialize();
    } catch (JOSEException e) {
      throw new IllegalStateException("could not sign a token", e);
    }
  }

  public static Optional<Claims> read(String secret, String token) {
    return parse(secret, token)
        .map(
            claims -> {
              try {
                return new Claims(
                    ((Number) claims.getClaim("id")).intValue(),
                    claims.getJWTID(),
                    claims.getIssueTime().toInstant(),
                    claims.getExpirationTime().toInstant(),
                    (String) claims.getClaim("scope"));
              } catch (Exception e) {
                return null;
              }
            })
        .filter(java.util.Objects::nonNull);
  }

  /**
   * A preserved-format token, refused unless every field it must carry is there and the path it
   * names ends in the suffix its format is stored under.
   */
  public static Optional<PreservedClaims> readPreserved(String secret, String token) {
    return parse(secret, token)
        .map(
            claims -> {
              try {
                if (!PRESERVED_FORMAT_SCOPE.equals(claims.getClaim("scope"))) return null;
                int format = ((Number) claims.getClaim("format")).intValue();
                String filePath = (String) claims.getClaim("filePath");
                String suffix = FilePaths.suffix(format);
                if (suffix == null || filePath == null || !filePath.endsWith(suffix)) return null;
                return new PreservedClaims(
                    ((Number) claims.getClaim("linkId")).intValue(),
                    filePath,
                    format,
                    claims.getIssueTime().toInstant(),
                    claims.getExpirationTime().toInstant());
              } catch (Exception e) {
                return null;
              }
            })
        .filter(java.util.Objects::nonNull);
  }

  private static Optional<JWTClaimsSet> parse(String secret, String token) {
    try {
      SignedJWT jwt = SignedJWT.parse(token);
      if (!jwt.verify(new MACVerifier(key(secret)))) return Optional.empty();
      return Optional.of(jwt.getJWTClaimsSet());
    } catch (ParseException | JOSEException e) {
      return Optional.empty();
    }
  }

  /** SPEC-001 R13 — the five lifetimes an API token can be asked for, in days. */
  public static int expiryDays(int tokenExpiry) {
    return switch (tokenExpiry) {
      case 1 -> 30;
      case 2 -> 60;
      case 3 -> 90;
      case 4 -> 73_000;
      default -> 7;
    };
  }
}

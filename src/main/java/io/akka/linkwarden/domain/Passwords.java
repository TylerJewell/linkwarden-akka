package io.akka.linkwarden.domain;

import org.mindrot.jbcrypt.BCrypt;

/**
 * How a password is stored and checked. SPEC-001 R16.
 *
 * <p>bcrypt at cost ten, which is the original's, so a hash written by either system is readable
 * by the other and an account survives a move between them.
 */
public final class Passwords {

  private static final int COST = 10;

  private Passwords() {}

  public static String hash(String plain) {
    return BCrypt.hashpw(plain == null ? "" : plain, BCrypt.gensalt(COST));
  }

  public static boolean matches(String plain, String hashed) {
    if (plain == null || hashed == null || hashed.isEmpty()) return false;
    try {
      return BCrypt.checkpw(plain, hashed);
    } catch (IllegalArgumentException e) {
      // A stored value that is not a bcrypt hash cannot match anything, and asking is not an
      // error the caller can do anything about.
      return false;
    }
  }
}

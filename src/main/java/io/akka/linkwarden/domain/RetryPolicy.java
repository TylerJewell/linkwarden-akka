package io.akka.linkwarden.domain;

import java.time.Duration;

/**
 * SPEC-001 R20. The source has no rule here — a failed attempt marks the link preserved and is
 * never returned to — so this is a rule the port was given rather than one it copied.
 *
 * <p>Attempt 1 runs immediately; the wait before attempt n is base × 2^(n-2), so with the
 * five-second base the waits are 5, 10 and 20 seconds and the fourth failure gives up.
 */
public record RetryPolicy(int maxAttempts, Duration baseDelay) {

  public static final RetryPolicy DEFAULT = new RetryPolicy(4, Duration.ofSeconds(5));

  public boolean hasAnotherAttempt(int attemptsMade) {
    return attemptsMade < maxAttempts;
  }

  public Duration delayBefore(int attemptNumber) {
    if (attemptNumber <= 1) {
      return Duration.ZERO;
    }
    return baseDelay.multipliedBy(1L << (attemptNumber - 2));
  }
}

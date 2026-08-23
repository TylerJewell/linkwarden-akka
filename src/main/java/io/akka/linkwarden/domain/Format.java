package io.akka.linkwarden.domain;

/**
 * The five per-format outcomes a link carries, as a closed set.
 *
 * <p>Closed rather than a string because an event handler replaying history must not be able to
 * fail: with a string there is an unknown-value branch, and the only honest thing that branch can
 * do during replay is throw, which makes the entity unrecoverable.
 */
public enum Format {
  IMAGE,
  PDF,
  READABLE,
  MONOLITH,
  PREVIEW
}

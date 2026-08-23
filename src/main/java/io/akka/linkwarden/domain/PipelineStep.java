package io.akka.linkwarden.domain;

/** The steps an attempt can run, named as SPEC-001 §3 names them. */
public enum PipelineStep {
  IMAGE_PREVIEW,
  FETCH_IMAGE,
  FETCH_PDF,
  META_DESCRIPTION,
  PREVIEW,
  READABILITY,
  SCREENSHOT_AND_PDF,
  MONOLITH
}

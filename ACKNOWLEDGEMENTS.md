# What this port took from linkwarden, and what it did not

`linkwarden/linkwarden` is licensed **AGPL-3.0**, © the Linkwarden authors.

**No source was copied into the rebuild.** Every file under `src/` was written for this
port. `frontend/` is a separate matter and is covered below. The
port was built from a specification (`specs/SPEC-001-linkwarden.md`) written before any
implementation existed, and that specification was written from answers produced by
*running* linkwarden's own code (`probes/source_probe/probe.ts`) rather than by
transcribing it.

## Strings that occur in both, and why

`python toolkit/copied_strings.py linkwarden --source linkwarden-src` pulls every literal
of ten characters or more out of the rebuild and names the ones that also occur in the
clone. It found seventeen. Each is below with a sentence, because a shared string is not
by itself a copy and neither is it nothing.

**Field names the two systems must agree on** — `archiveAsScreenshot`,
`archiveAsMonolith`, `archiveAsPDF`, `archiveAsReadable`, `archiveAsWaybackMachine`,
`contentType`, `metaDescription`, `lastPreserved`, `indexVersion`. These are linkwarden's
names for the fields SPEC-001 §2 governs, and they appear in the port only in
`BenchmarkRunner`, which reads a workload file that describes a link in linkwarden's own
terms so that the same workload can be handed to both systems. A benchmark that renamed
them would be comparing two things it had itself made different.

**Path shapes the two systems must agree on** — `archives/preview/`, `_readability.json`.
The paths an attempt writes are part of what SPEC-001 R7 to R11 decide, and the benchmark
compares them character by character. Producing a different path would not be
independence, it would be a disagreement the report would have to explain.

**`unavailable`** — the literal linkwarden stores in a format field when that format
produced nothing. SPEC-001 R7, R8 and R15 are all about the difference between that value
and an absent one, so the port stores the same string. It is a value in a data model, not
a line of code.

**`image/jpeg`** — an IANA media type. Both systems read the same header from the same
web.

**`completion`, `eligibility`** — ordinary English words that happen to be ten characters
or more. They are workload-kind names this port invented; the match is a coincidence of
the scan's length threshold.

**` already exists`, ` not found`** — fragments of this port's own error messages
(`"link " + linkId + " already exists"`). linkwarden contains those words too, as any
codebase does. Nothing was reproduced: the port's messages were written for the port, and
they were checked against linkwarden's rather than assumed to differ.

## The interface

`frontend/` is linkwarden's own web application, copied from the clone and **kept under
its own AGPL-3.0 licence** (`frontend/LICENSE.md`). One file in it is changed:
`packages/router/dashboardData.tsx`, whose repeated request for the dashboard's links is
replaced by a subscription to this service. Nothing else was touched — components,
styling, routes and assets are linkwarden's, unmodified, which is what makes the screen
comparison in `linkwarden-port/gui/` a comparison rather than a review of taste.

## What was reused as an artefact

- **`gui/baseline/links.png`** is a screenshot of linkwarden's own interface, captured by
  running `ghcr.io/linkwarden/linkwarden:latest` and driving it through its own sign-up
  form and its own API (`gui/seed_source.py`). It is a picture of the original, kept so the
  port's own interface can be compared against it, and it is not part of the rebuild.
- **`gui/docker-compose.yml`** runs the original's published image. It is this port's file;
  it names linkwarden's image and nothing else of linkwarden's.
- **`probes/source_probe/`** imports linkwarden's own TypeScript files from the clone at
  run time and stands in for the store, the network, the browser, the HTML parser and the
  image decoder. Those imports read the clone; nothing is copied into this repository.

## Licence consequence

linkwarden is AGPL-3.0. This port is an independent implementation of behaviour observed
by running linkwarden, not a derivative of its source, and it carries no linkwarden code.
The repository is private. Making it public is a decision to take separately, with the
question of whether behaviour-level reimplementation of an AGPL work should nonetheless be
released under AGPL settled deliberately rather than as a side effect of a push.

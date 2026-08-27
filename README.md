# linkwarden-akka

Saves web links, organises them into collections, shares them, searches them, and keeps
copies of the pages they point at.

A port of [linkwarden/linkwarden](https://github.com/linkwarden/linkwarden) onto **Akka**,
built with **Akka Specify**.

![The dashboard, showing three saved links after the pipeline has run over them](docs/images/console.png)

---

## Where it came from

linkwarden is a self-hosted place to save, organise and keep copies of web pages. It was
ported to derive a specification format precise enough to regenerate a system on a
different stack — the port is the vehicle, the specification is the deliverable.

The specifications the port was generated from are in
[TylerJewell/akka-specify-harness](https://github.com/TylerJewell/akka-specify-harness)
under `linkwarden-port/`.

---

## linkwarden/linkwarden → this port

📉 19,225 TypeScript lines → **8,556 Java lines**<br>
📁 235 files → **85 files**<br>
🧪 45 tests → **256 tests**<br>
🎯 not measured → **198 of 200 requests answered identically**<br>
⚡ 55 milliseconds per request → **118 milliseconds per request**<br>
⚡ 88,530 nanoseconds per preservation decision → **15,147 nanoseconds**<br>
🖼️ 102 interface files → **102 interface files, 4 changed**

Full method and the numbers that did *not* make this list:
[`bench/REPORT.md`](https://github.com/TylerJewell/akka-specify-harness/blob/main/linkwarden-port/bench/REPORT.md).

---

## What it took to build

⏱️ **80.5 hours** from the first command to the published repository, **8.2** of them active<br>
💬 **2,039** exchanges with the model<br>
✍️ **1,644,480** tokens written by the model, **797,247,217** counting everything sent and re-sent<br>
🙋 **0** questions to a human<br>
🧪 **256** tests

```bash
python toolkit/tokens.py --port linkwarden    # turns, tokens, elapsed and active time
```

The record of every question, and where the time went, is in
[`port-log/`](https://github.com/TylerJewell/akka-specify-harness/tree/main/port-log).

---

## What it does

From the specification:

- **A name and an address each belong to one account, and the check happens as the account
  is made.** Two people signing up with the same name at the same moment cannot both
  succeed, whatever order the requests arrive in.
- **Rights on a collection reach every collection under it, and are read from the top.**
  Sharing a folder shares everything inside it, and taking the sharing away takes it away
  from everything inside it too.
- **Deleting a collection you own deletes everything under it, deepest first.** Its links,
  its shares, its search entries and its stored files go with it; a person who was only a
  member loses their membership and nothing else.
- **A link is picked up for copying when it has an address and has never been through the
  pipeline.** One field decides it, so asking for a page to be kept again is a single
  write.
- **A tag with any copying setting on it decides for the whole link.** A tag that turns
  every format off turns every format off, and the person who owns the link is not
  consulted.
- **What the page answered with decides what is kept.** A page answering as a PDF has its
  PDF saved and is never opened in a browser; one answering as an image has the image saved
  and a small copy made from it.
- **Every format still unanswered when an attempt ends is recorded as unavailable.** That
  happens on the failing path as well as the succeeding one, so nothing is left looking
  like it is still being worked on.
- **Turning on duplicate prevention only affects links saved afterwards.** The links
  already there are left alone, and the check is against the address exactly as saved.
- **Nobody can save more links than their allowance, and the count is of what they own.**
  Links in a collection somebody shared with them do not count against them.
- **An address that resolves to a machine on the local network is refused before anything
  fetches it.** That covers the copying pipeline, the feed reader and the page-title
  lookup alike.

Generated documentation lives at [`docs/index.html`](docs/index.html) — open it in a
browser for the entity diagram, the interaction path, and the component reference.

---

## Design decisions

**Per-link workflow.** Each link gets its own small program that remembers how far it got,
so if the machine stops halfway through nothing is lost and nothing is done twice. A page
interrupted at the third of five steps carries on from the third step rather than starting
again.

**A key of its own for anything that must be right immediately.** A name somebody has just
taken, a tag somebody has just made, the links a collection has just been given — each is
written where the next request reads it, rather than into a list that catches up a moment
later. Somebody who signs up can sign in on their very next request.

**Waiting longer after each failure.** A page that fails is retried after five seconds,
then ten, then twenty, rather than immediately. A site that is briefly down gets a second
chance without being hammered while it is struggling.

**One record of everything that happened to a link.** Every answer the pipeline produced is
appended to a list that is never rewritten, rather than overwriting a row. Anyone can ask
what was tried, in what order, and what came back.

**The screen is linkwarden's own.** Every page, every button and every style is the file
linkwarden ships, with four files changed so the data comes from here. Anybody who knows
linkwarden already knows this.

---

## Running it — the short path

You do not need Java, Maven, or the Akka CLI installed. Akka Specify installs them for you.

**1. Install Akka Specify** in Claude Code:

```
/plugin marketplace add akka/ai-marketplace
/plugin install akka@akka-ai-marketplace
```

Restart Claude Code when it asks.

**2. Give it this prompt:**

> Clone https://github.com/TylerJewell/linkwarden-akka into a new directory and open it.
> Then run /akka:setup to install everything this project needs, and /akka:build to
> compile it, run the tests, and start it locally.

**3. Open** http://localhost:3000.

---

## Running it — the developer path

### Requirements

- Java 21 or newer
- Maven 3.9 or newer
- An Akka download token — run `akka code token` once
- Node 20 or newer and Yarn, for the screen

### Start the service

```bash
export NEXTAUTH_SECRET=anything-long-and-secret
mvn compile
akka local run
```

The service starts on **port 9160**. It stores nothing outside itself except the copies of
pages, which go under `data/archives`.

### Start the screen

The interface under `frontend/` is linkwarden's own. Four files are different: one turns
the browser's session cookie into the header this service reads, one points the data layer
at this service, one feeds the dashboard from a held-open connection instead of asking
again every few seconds, and one routes the browser's requests through.

```bash
cd frontend
yarn install
yarn workspace @linkwarden/web dev
```

It opens on **port 3000** and talks to the service on 9160. The service holds every record;
the screen keeps only the session cookie. `DATABASE_URL` must still name a Postgres, because
the sign-in machinery the screen kept builds a database client as it loads even though the
sign-in itself now asks the service.

---

## Configuration

Every variable linkwarden reads is read here under the same name. The ones without a
linkwarden equivalent are marked.

| Variable | Default | Notes |
|---|---|---|
| `NEXTAUTH_SECRET` | none | signs sessions and the links that let a copy be viewed; the service refuses to sign anybody in without it |
| `NEXT_PUBLIC_ADMIN` | `1` | which account is the administrator |
| `NEXT_PUBLIC_DISABLE_REGISTRATION` | `false` | whether anybody new may sign up |
| `NEXT_PUBLIC_CREDENTIALS_ENABLED` | `true` | whether a name and password may be used to sign in |
| `NEXT_PUBLIC_DEMO` | `false` | turns every request that changes something into one sentence of refusal |
| `NEXT_PUBLIC_DEMO_USERNAME`, `NEXT_PUBLIC_DEMO_PASSWORD` | none | shown on the sign-in screen when the above is on |
| `MAX_LINKS_PER_USER` | `30000` | how many links one account may own |
| `IMPORT_LIMIT` | `50000` | how many links one imported file may carry |
| `RSS_SUBSCRIPTION_LIMIT_PER_USER` | `20` | how many feeds one account may follow |
| `RSS_POLLING_INTERVAL_MINUTES` | `60` | how often a feed is read |
| `PAGINATION_TAKE_COUNT` | `50` | how many links come back in one page of results |
| `SEARCH_FILTER_LIMIT` | `100` | how many collections or tags a search may name |
| `NEXT_PUBLIC_MAX_FILE_BUFFER` | `10` | the largest file, in megabytes, that may be uploaded |
| `STORAGE_FOLDER` | `data` | where copies of pages are written |
| `NEXT_PUBLIC_USER_CONTENT_DOMAIN` | none | the address a stored copy is served from |
| `ALLOW_PRIVATE_NETWORK_ACCESS` | `false` | whether addresses on the local network may be fetched |
| `EMAIL_FROM`, `EMAIL_SERVER` | none | until both are set, every route that would send mail says so |
| `STRIPE_SECRET_KEY`, `NEXT_PUBLIC_STRIPE` | none | until set, everything answers as an instance with no billing |
| `NEXT_PUBLIC_TRIAL_PERIOD_DAYS`, `NEXT_PUBLIC_REQUIRE_CC`, `NEXT_PUBLIC_STRIPE_BILLING_PORTAL_URL` | none | published to the screen as linkwarden publishes them |
| `NEXT_PUBLIC_GOOGLE_ENABLED`, `NEXT_PUBLIC_MOBILE_APP_REDIRECT_ENABLED` | none | published to the screen as linkwarden publishes them |
| `OPENAI_API_KEY`, `ANTHROPIC_API_KEY`, `AZURE_API_KEY`, `OPENROUTER_API_KEY`, `PERPLEXITY_API_KEY`, `NEXT_PUBLIC_OLLAMA_ENDPOINT_URL` | none | any one of them makes the screen offer automatic tagging |
| `INSTANCE_VERSION` | none | reported by the configuration route |
| `WORKER_INTERVAL` | `10` | seconds between passes of the copying, indexing and feed-reading loop — no linkwarden equivalent |
| `akka.javasdk.dev-mode.http-port` | `9160` | the port the service listens on — no linkwarden equivalent |

---

## Where it differs from linkwarden/linkwarden

Everything not listed here behaves the same way on purpose, including the parts that look
like mistakes.

- **A collection that is its own parent can be deleted.** linkwarden lets a collection be
  made its own parent, and then deleting it never returns — the request that asks is still
  open fifteen seconds later, and the collection is still there afterwards. This port stops
  at a collection it has already walked past and deletes it. It was chosen because a
  request that never answers is not an answer this port could copy, and every other
  collection delete matches linkwarden exactly.
- **An uploaded file arrives inside a JSON body rather than as a form.** linkwarden takes
  the file as one part of a multipart form. This port takes the same file with its
  characters encoded into a JSON field and its media type beside it, because that is the
  only shape the thing this port runs on will hand to a request handler. Everything about
  which media types are allowed, the size limit and the two picture formats that exclude
  each other is the same.
- **The screen is fed by a held-open connection.** linkwarden's dashboard asks the server
  for its links again every few seconds while anything is still being worked on. This port
  sends an update when there is one, over an address linkwarden does not have. A change
  appears as soon as it happens rather than up to a few seconds later, and if the
  connection drops the page reopens it rather than quietly showing an old answer. The
  original never had to say what happens across a dropped connection, and this port had to
  be given an answer.
- **Copies of pages are not made.** linkwarden opens a browser, screenshots the page,
  prints it to a PDF, inlines it with an external program and extracts its text. This port
  is handed what those would have answered and decides what to do with it. Everything about
  which copies are made, in what order, and what is recorded is the same; the bytes are
  not produced, so a stored copy is a decision about a file rather than the file.
- **Links are searched without a search engine.** linkwarden hands indexed links to a
  separate search service and falls back to matching on the name, the address, the
  description and the tags when there is none. This port decides which links are due to be
  indexed and records the outcome, and always answers a search the fallback way. Every
  search compared gives the same answer; a deployment that runs the search service would
  get different ordering for long text, and that was not checked.
- **Tags are not suggested by a language model.** linkwarden can ask a model to read a page
  and propose tags. This port decides which links are due for that, builds the same request
  and applies the same filtering, the same cap of how many tags survive and the same casing
  rule to whatever comes back, and asks nothing. The suggestions themselves are `not
  checked`.
- **Submitting to the Internet Archive.** linkwarden sends the address to archive.org and
  does not read the answer. This port records that the setting is on and sends nothing.
- **Nothing is charged.** linkwarden talks to Stripe and to the two mobile app stores. This
  port keeps the subscription state, the seat arithmetic and every answer the interface
  gives while billing is switched off, and places no charge. Behaviour with billing
  switched on is `not checked`.
- **No mail is delivered.** linkwarden hands an envelope to a mail server for verification,
  password reset and invitations. This port mints the same token with the same lifetime,
  applies the same limit on asking for another, and gives the same answers; nothing is
  sent. Behaviour with a mail server configured is `not checked`.
- **A batch is not a unit of work.** linkwarden takes a group of links and works through
  them together in one process. This port takes one link at a time, each with its own
  program. The rules about which links a batch would hold, and how a batch is shared
  between people, are rebuilt and compared, but nothing here processes several at once
  because one of them failing does not affect another.
- **A failed attempt is tried again.** linkwarden records the link as done in the same
  block that reports the failure, so a page that could not be reached is marked exactly as
  a page with nothing to keep, and the only way back is a person asking for it again. This
  port tries three more times, waiting five, ten and twenty seconds, and marks the link the
  same way only once those are used up. It was chosen because the thing this port runs on
  makes a durable, resumable wait the cheap option rather than the expensive one.
- **A failed indexing attempt is tried again.** linkwarden catches the failure, ignores it,
  and records the link as indexed anyway, so nothing was indexed and nothing will be. This
  port records the link as indexed only when the index accepted it. Same reasoning as
  above.
- **The Android and iPhone applications are not here.** linkwarden ships both, and two
  addresses exist only for them. This port has neither, and those two addresses are the
  only ones from linkwarden's list that it does not answer.
- **Ordering by identity.** linkwarden orders links by the number its database gave them,
  which is also the order they were saved in. This port hands out the same ascending
  numbers itself and orders by them. The two agree on every case compared; a linkwarden
  database that hands out numbers out of order would not, and that was not checked.
- **The page's copies do not display.** The small images on the dashboard are files, and
  this port keeps the decision about where a file goes without keeping the file. The card
  shows the grey block linkwarden shows for a page whose copy is missing, in the two places
  where linkwarden would show a picture. Not checked against every screen — only the
  dashboard was compared.

---

## Licence

linkwarden is AGPL-3.0, © the Linkwarden authors. This port reimplements the behaviour
without copied source; the interface under `frontend/` is linkwarden's own and remains
under AGPL-3.0. See `ACKNOWLEDGEMENTS.md`.

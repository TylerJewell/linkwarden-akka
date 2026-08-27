# What this port took from linkwarden, and what it did not

`linkwarden/linkwarden` is licensed **AGPL-3.0**, © the Linkwarden authors. This port is a
derived work and carries the same licence.

## The rebuild

**No source was copied into it.** Every file under `src/` was written for this port, from
`specs/SPEC-001-linkwarden.md` — a specification written before any implementation existed,
from answers produced by *running* linkwarden rather than by transcribing it. What the two
have in common is text on the wire, and that is the subject of the list below.

## The interface

`frontend/` **is** linkwarden's own web interface, copied whole. RENDERING.md R3 has a port
reuse the interface the source already ships rather than build one, so that the appearance
comparison has a subject and so that the rebuild is the only variable. Four files differ from
the clone, and every one of them is the data layer:

| File | What changed |
|---|---|
| `apps/web/middleware.ts` | New. Sends every `/api/v1` and `/api/v2` call on to the rebuild with this session's token attached; the screens carry a cookie and the rebuild reads a bearer header. |
| `apps/web/pages/api/v1/auth/[...nextauth].ts` | Signing in asks the rebuild rather than reading the database, and the token it answers with is carried in the session. |
| `apps/web/lib/client/getServerSideProps.ts` | Which language a page renders in is read from the rebuild rather than from the database. |
| `packages/router/dashboardData.tsx` | The dashboard's repeated request is replaced by a subscription (RENDERING.md R1). |

Two `.env` files are this port's own configuration and are not part of the copy.

```bash
diff -r --exclude=node_modules --exclude=.next linkwarden-akka/frontend linkwarden-src
```

## Strings that occur in both, and why

`python toolkit/copied_strings.py linkwarden --source linkwarden-src` pulls every literal of
ten characters or more out of the rebuild and names the ones that also occur in the clone. It
finds 437, and every one of them is below under the reason it is shared.

They are of four kinds, and none of the four is a copy of code. They are the **contract**: the
words an answer carries, the names a setting is read under, the addresses a caller sends to,
and the fields a body holds. A rebuild that changed any of them would not be more independent
— it would answer differently, and the specification it was built from names each one as a
rule.

### The sentences an answer carries

Each of these is an answer a client already written against linkwarden reads, and a
rebuild that reworded any of them would answer differently. Reproduced on purpose.

- `All links updated successfully`
- `An internal occurred, please contact the support team.`
- `Archived format not found.`
- `Azure AD B2C`
- `Collection is not accessible.`
- `Collection not accessible`
- `Collection not found.`
- `Dashboard data fetched successfully.`
- `EVE Online`
- `Each collection owner can only have a maximum of `
- `Email is already in use.`
- `Email is already verified.`
- `Email is not configured.`
- `Email not verified, please verify your email to continue using Linkwarden.`
- `Email or Username already exists.`
- `File inaccessible.`
- `File not found.`
- `Forbidden.`
- `Import file exceeds the `
- `Invalid URL`
- `Invalid URL.`
- `Invalid archived format token.`
- `Invalid credentials.`
- `Invalid credentials. You might need to reset your password if you're sure you already`
- `Invalid email.`
- `Invalid file type.`
- `Invalid parameters.`
- `Invalid request body provided.`
- `Invalid request.`
- `Invalid token.`
- `Invalid user content host.`
- `Link already exists`
- `Link deleted.`
- `Link is being archived.`
- `Link not found.`
- `Links deleted.`
- `Linkwarden (Server-Side Fetch)`
- `MB size limit.`
- `Method not allowed`
- `Missing archived format token.`
- `Missing format`
- `Monolith archive access must use the user content domain when it is configured.`
- `NEXTAUTH_SECRET is not configured.`
- `NOT collectionIsPublic = true`
- `NOT pinnedBy = `
- `New password must be different from the old password.`
- `No unverified emails found.`
- `No user found with that email.`
- `Old password is incorrect.`
- `Omnivore Imports`
- `Only http:// and https:// URLs can be archived.`
- `PNG or JPEG file already exists.`
- `Password has been reset successfully.`
- `Password is required.`
- `Password reset email sent.`
- `Permission denied.`
- `Please choose a valid collection.`
- `Please choose a valid highlight.`
- `Please choose a valid link.`
- `Please choose a valid name for the tag.`
- `Please choose a valid token.`
- `Please choose valid links.`
- `Please fill out all the fields.`
- `Please verify your email address before logging in.`
- `RSS Subscription with that name already exists`
- `RSS subscription deleted.`
- `RSS subscription not found.`
- `Registration is disabled.`
- `Some links failed to update`
- `Sorry, we couldn't process your file.`
- `Sorry, we couldn't process your file. Please ensure it doesn't exceed `
- `Sorry, we couldn't process your file. Please ensure it's in [`
- `Subscription not found.`
- `Tag names should be unique.`
- `Tag not found.`
- `This action is disabled because this is a read-only demo of Linkwarden.`
- `This format cannot be uploaded.`
- `Token with that name already exists.`
- `Too many requests. Please try again later.`
- `URL hostname could not be resolved.`
- `URL hostname did not resolve to a public IP.`
- `URL hostname lookup failed.`
- `URL resolves to a blocked internal IP address.`
- `URL resolves to a blocked internal hostname.`
- `United Effects`
- `Unknown Device`
- `Untitled Collection`
- `User account and all related data deleted successfully.`
- `User content domain is not configured.`
- `User has no password. Please create one from the password settings page.`
- `User not found.`
- `Username is taken.`
- `Username not found.`
- `Verification email sent.`
- `Verification link is invalid or has expired.`
- `Worker stats fetched successfully.`
- `You are not a subscriber, feel free to reach out to us at support@linkwarden.app if`
- `You are not authorized to create a sub-collection here.`
- `You can't move a link to/from a collection you don't own.`
- `You do not have permission to add a link to this collection`
- `You don't have access to this collection.`
- `You have reached the limit of `
- `You must be logged in.`
- `Your session has expired, please log in again.`
- `Your subscription has reached the maximum number of links allowed.`

### The names of settings, and the values a setting may take

Read from the process environment, and several of them published by
`GET /api/v1/config`. A rebuild that spelled one differently would be configured by a
different file.

- `AI_ENABLED`
- `ALLOW_PRIVATE_NETWORK_ACCESS`
- `ANTHROPIC_API_KEY`
- `APPLE_CUSTOM_NAME`
- `ARCHIVE_TAKE_COUNT`
- `ATLASSIAN_CUSTOM_NAME`
- `AUTH0_CUSTOM_NAME`
- `AUTHELIA_CUSTOM_NAME`
- `AUTHENTIK_CUSTOM_NAME`
- `AZURE_AD_B2C_CUSTOM_NAME`
- `AZURE_AD_CUSTOM_NAME`
- `AZURE_API_KEY`
- `BATTLENET_CUSTOM_NAME`
- `BOX_CUSTOM_NAME`
- `COGNITO_CUSTOM_NAME`
- `COINBASE_CUSTOM_NAME`
- `COLLECTION`
- `DEMO_PASSWORD`
- `DEMO_USERNAME`
- `DISABLE_REGISTRATION`
- `DISCORD_CUSTOM_NAME`
- `DROPBOX_CUSTOM_NAME`
- `DUENDE_IDS6_CUSTOM_NAME`
- `EMAIL_FROM`
- `EMAIL_NOT_VERIFIED`
- `EMAIL_PROVIDER`
- `EMAIL_SERVER`
- `EVEONLINE_CUSTOM_NAME`
- `FACEBOOK_CUSTOM_NAME`
- `FACEIT_CUSTOM_NAME`
- `FORTYTWO_CUSTOM_NAME`
- `FOURSQUARE_CUSTOM_NAME`
- `FRESHBOOKS_CUSTOM_NAME`
- `FUSIONAUTH_CUSTOM_NAME`
- `GITHUB_CUSTOM_NAME`
- `GITLAB_CUSTOM_NAME`
- `GOOGLE_CUSTOM_NAME`
- `GOOGLE_ENABLED`
- `HUBSPOT_CUSTOM_NAME`
- `IDS4_CUSTOM_NAME`
- `IMPORT_LIMIT`
- `INSTANCE_VERSION`
- `KAKAO_CUSTOM_NAME`
- `KEYCLOAK_CUSTOM_NAME`
- `LINE_CUSTOM_NAME`
- `LINKEDIN_CUSTOM_NAME`
- `MAILCHIMP_CUSTOM_NAME`
- `MAILRU_CUSTOM_NAME`
- `MAX_FILE_BUFFER`
- `MAX_LINKS_PER_USER`
- `MOBILE_APP_REDIRECT_ENABLED`
- `NAVER_CUSTOM_NAME`
- `NETLIFY_CUSTOM_NAME`
- `NEXTAUTH_SECRET`
- `NEXT_PUBLIC_ADMIN`
- `NEXT_PUBLIC_APPLE_ENABLED`
- `NEXT_PUBLIC_ATLASSIAN_ENABLED`
- `NEXT_PUBLIC_AUTH0_ENABLED`
- `NEXT_PUBLIC_AUTHELIA_ENABLED`
- `NEXT_PUBLIC_AUTHENTIK_ENABLED`
- `NEXT_PUBLIC_AZURE_AD_B2C_ENABLED`
- `NEXT_PUBLIC_AZURE_AD_ENABLED`
- `NEXT_PUBLIC_BATTLENET_ENABLED`
- `NEXT_PUBLIC_BOX_ENABLED`
- `NEXT_PUBLIC_COGNITO_ENABLED`
- `NEXT_PUBLIC_COINBASE_ENABLED`
- `NEXT_PUBLIC_CREDENTIALS_ENABLED`
- `NEXT_PUBLIC_DEMO`
- `NEXT_PUBLIC_DEMO_PASSWORD`
- `NEXT_PUBLIC_DEMO_USERNAME`
- `NEXT_PUBLIC_DISABLE_REGISTRATION`
- `NEXT_PUBLIC_DISCORD_ENABLED`
- `NEXT_PUBLIC_DROPBOX_ENABLED`
- `NEXT_PUBLIC_DUENDE_IDS6_ENABLED`
- `NEXT_PUBLIC_EMAIL_PROVIDER`
- `NEXT_PUBLIC_EVEONLINE_ENABLED`
- `NEXT_PUBLIC_FACEBOOK_ENABLED`
- `NEXT_PUBLIC_FACEIT_ENABLED`
- `NEXT_PUBLIC_FORTYTWO_ENABLED`
- `NEXT_PUBLIC_FOURSQUARE_ENABLED`
- `NEXT_PUBLIC_FRESHBOOKS_ENABLED`
- `NEXT_PUBLIC_FUSIONAUTH_ENABLED`
- `NEXT_PUBLIC_GITHUB_ENABLED`
- `NEXT_PUBLIC_GITLAB_ENABLED`
- `NEXT_PUBLIC_GOOGLE_ENABLED`
- `NEXT_PUBLIC_HUBSPOT_ENABLED`
- `NEXT_PUBLIC_IDS4_ENABLED`
- `NEXT_PUBLIC_KAKAO_ENABLED`
- `NEXT_PUBLIC_KEYCLOAK_ENABLED`
- `NEXT_PUBLIC_LINE_ENABLED`
- `NEXT_PUBLIC_LINKEDIN_ENABLED`
- `NEXT_PUBLIC_MAILCHIMP_ENABLED`
- `NEXT_PUBLIC_MAILRU_ENABLED`
- `NEXT_PUBLIC_MAX_FILE_BUFFER`
- `NEXT_PUBLIC_MOBILE_APP_REDIRECT_ENABLED`
- `NEXT_PUBLIC_NAVER_ENABLED`
- `NEXT_PUBLIC_NETLIFY_ENABLED`
- `NEXT_PUBLIC_OIDC_ENABLED`
- `NEXT_PUBLIC_OKTA_ENABLED`
- `NEXT_PUBLIC_OLLAMA_ENDPOINT_URL`
- `NEXT_PUBLIC_ONELOGIN_ENABLED`
- `NEXT_PUBLIC_OSSO_ENABLED`
- `NEXT_PUBLIC_OSU_ENABLED`
- `NEXT_PUBLIC_PATREON_ENABLED`
- `NEXT_PUBLIC_PINTEREST_ENABLED`
- `NEXT_PUBLIC_PIPEDRIVE_ENABLED`
- `NEXT_PUBLIC_REDDIT_ENABLED`
- `NEXT_PUBLIC_REQUIRE_CC`
- `NEXT_PUBLIC_RSS_POLLING_INTERVAL_MINUTES`
- `NEXT_PUBLIC_SALESFORCE_ENABLED`
- `NEXT_PUBLIC_SLACK_ENABLED`
- `NEXT_PUBLIC_SPOTIFY_ENABLED`
- `NEXT_PUBLIC_STRAVA_ENABLED`
- `NEXT_PUBLIC_STRIPE`
- `NEXT_PUBLIC_STRIPE_BILLING_PORTAL_URL`
- `NEXT_PUBLIC_SYNOLOGY_ENABLED`
- `NEXT_PUBLIC_TODOIST_ENABLED`
- `NEXT_PUBLIC_TRIAL_PERIOD_DAYS`
- `NEXT_PUBLIC_TWITCH_ENABLED`
- `NEXT_PUBLIC_UNITED_EFFECTS_ENABLED`
- `NEXT_PUBLIC_USER_CONTENT_DOMAIN`
- `NEXT_PUBLIC_VK_ENABLED`
- `NEXT_PUBLIC_WIKIMEDIA_ENABLED`
- `NEXT_PUBLIC_WORDPRESS_ENABLED`
- `NEXT_PUBLIC_YANDEX_ENABLED`
- `NEXT_PUBLIC_ZITADEL_ENABLED`
- `NEXT_PUBLIC_ZOHO_ENABLED`
- `NEXT_PUBLIC_ZOOM_ENABLED`
- `NEXT_PUBLIC_ZOOM_ENABLED_ENABLED`
- `OIDC_CUSTOM_NAME`
- `OKTA_CUSTOM_NAME`
- `ONELOGIN_CUSTOM_NAME`
- `OPENAI_API_KEY`
- `OPENROUTER_API_KEY`
- `OSSO_CUSTOM_NAME`
- `OSU_CUSTOM_NAME`
- `PAGINATION_TAKE_COUNT`
- `PATREON_CUSTOM_NAME`
- `PERPLEXITY_API_KEY`
- `PINNED_LINKS`
- `PINTEREST_CUSTOM_NAME`
- `PIPEDRIVE_CUSTOM_NAME`
- `RECENT_LINKS`
- `REDDIT_CUSTOM_NAME`
- `REQUIRE_CC`
- `RSS_POLLING_INTERVAL_MINUTES`
- `RSS_SUBSCRIPTION_LIMIT_PER_USER`
- `SALESFORCE_CUSTOM_NAME`
- `SEARCH_FILTER_LIMIT`
- `SLACK_CUSTOM_NAME`
- `SPOTIFY_CUSTOM_NAME`
- `STORAGE_FOLDER`
- `STRAVA_CUSTOM_NAME`
- `STRIPE_BILLING_PORTAL_URL`
- `STRIPE_ENABLED`
- `STRIPE_SECRET_KEY`
- `SYNOLOGY_CUSTOM_NAME`
- `TODOIST_CUSTOM_NAME`
- `TRIAL_PERIOD_DAYS`
- `TWITCH_CUSTOM_NAME`
- `UNITED_EFFECTS_CUSTOM_NAME`
- `USER_CONTENT_DOMAIN`
- `VK_CUSTOM_NAME`
- `WIKIMEDIA_CUSTOM_NAME`
- `WORDPRESS_CUSTOM_NAME`
- `YANDEX_CUSTOM_NAME`
- `ZITADEL_CUSTOM_NAME`
- `ZOHO_CUSTOM_NAME`
- `ZOOM_CUSTOM_NAME`

### Addresses, and where a file is kept

The routes the surface serves and the paths a preserved format is written to. A data
directory written by either system is readable by the other, which is what makes the
paths part of the behaviour.

- `/api/v1/archives/`
- `/api/v1/auth`
- `/api/v1/auth/`
- `/api/v1/auth/request-verification-email`
- `/api/v1/config`
- `/api/v1/logins`
- `/api/v1/migration`
- `/api/v1/preserved/token`
- `/api/v1/preserved/view`
- `/api/v1/preserved/view?token=`
- `/api/v1/public`
- `/api/v1/public/collections/`
- `/api/v1/public/users/`
- `/api/v1/search`
- `/api/v1/session`
- `/api/v1/users`
- `/api/v1/users/`
- `/api/v1/users/me`
- `uploads/avatar/`

### Field names on the wire, and the values of the enumerations they carry

What a request carries and what an answer carries, down to the spelling. This is the
schema; renaming one is a different interface.

- ` RSS subscriptions.`
- ` answered `
- ` signed up with the current username/email.`
- ` you think this is an issue.`
- `(collectionOwnerId = `
- `) OR (collectionMemberIds = `
- `.localdomain`
- `.localhost`
- `/collections`
- `/getFavicon`
- `/highlights`
- `/migration`
- `/preservation`
- `/preserved/token`
- `/preserved/view`
- `/request-verification-email`
- `/reset-password`
- `/tags/merge`
- `/verify-email`
- `2001:db8::`
- `@example.com`
- `Authorization`
- `Cache-Control`
- `Content-Disposition`
- `Content-Security-Policy`
- `DuendeIdentityServer6`
- `Foursquare`
- `Freshbooks`
- `FusionAuth`
- `IdentityServer4`
- `Salesforce`
- `Unorganized`
- `User-Agent`
- `X-Content-Type-Options`
- `X-Robots-Tag`
- `[0-9a-f]{1,4}`
- `] format and doesn't exceed `
- `^[a-z0-9_-]{3,50}$`
- `acceptPromotionalEmails`
- `aiGenerated`
- `aiPredefinedTags`
- `aiTagExistingLinks`
- `aiTaggingMethod`
- `allAndRePreserve`
- `attachment; filename="`
- `attachment; filename=backup.json`
- `azure-ad-b2c`
- `broadcasthost`
- `buttonAuths`
- `clientSide`
- `collection`
- `collectionId`
- `collectionIsPublic`
- `collectionIsPublic = true`
- `collectionLinks`
- `collectionMemberIds`
- `collectionName`
- `collectionOrder`
- `collectionOwnerId`
- `collections`
- `content-type`
- `createdById`
- `created_at`
- `creationTimestamp`
- `creationTimestamp < `
- `creationTimestamp <= `
- `creationTimestamp > `
- `creationTimestamp >= `
- `credentialsEnabled`
- `dashboardSections`
- `description`
- `dismissedAnnouncementId`
- `duende-identityserver6`
- `emailEnabled`
- `emailVerified`
- `foursquare`
- `freshbooks`
- `fusionauth`
- `hasOAuthAccount`
- `hasPassword`
- `hasUnIndexedLinks`
- `iconWeight`
- `identity-server4`
- `importDate`
- `ip6-localhost`
- `ip6-loopback`
- `is_starred`
- `lastBuildDate`
- `lastPickedAt`
- `linksRouteTo`
- `newPassword`
- `newTagName`
- `nextCursor`
- `noindex, nofollow`
- `not configured`
- `numberOfPinnedLinks`
- `numberOfTags`
- `oldPassword`
- `parentSubscription`
- `parentSubscriptionId`
- `pinnedBy = `
- `pinnedLinks`
- `pinnedOnly`
- `preserved-format`
- `preventDuplicateLinks`
- `private, max-age=31536000, immutable`
- `private, no-store`
- `propagateToSubcollections`
- `public, max-age=3600, s-maxage=86400, stale-while-revalidate=604800`
- `readableFontFamily`
- `readableFontSize`
- `readableLineHeight`
- `readableLineWidth`
- `referredBy`
- `registrationDisabled`
- `removePreviousTags`
- `request-verification-email`
- `rssSubscriptions`
- `salesforce`
- `sans-serif`
- `searchQueryString`
- `sessionName`
- `startOffset`
- `subscriptions`
- `textContent`
- `time_added`
- `trialEndEmailSent`
- `united-effects`
- `unverifiedNewEmail`

### The thirteen the archiving pipeline shares

Named separately because the slice this port grew out of shared exactly these, and the
sentence each was given then still holds.

- `unavailable` — the value linkwarden stores in a format field when that format produced
  nothing. R52 is entirely about the difference between that value and an absent one, so the
  rebuild stores the same string. It is a value in a data model.
- `archiveAsScreenshot`, `archiveAsMonolith`, `archiveAsPDF`, `archiveAsReadable`,
  `archiveAsWaybackMachine` — the five settings R50 resolves between a link's tags and its
  owner, on the wire and in the specification.
- `lastPreserved`, `indexVersion`, `metaDescription` — three fields a rule writes back, named
  by R53, R57 and R51.
- `archives/preview/`, `_readability.json` — two of the paths R84 fixes.
- `Screenshot` — the name a download is offered under by R88.
- `image/jpeg` — a media type registered with IANA. Both systems read the same header from the
  same web.

### Four that are the same word in both and were not copied

- `statusCode` — the field `searchLinks` returns its status under, and the field the rebuild's
  dashboard answers under, because a client reading one reads the other. It is a field name on
  the wire.
- `completion`, `eligibility`, `contentType` — three keys in the benchmark's own workload file,
  which both sides read. They are the names of the questions being asked, chosen so that the
  two answer files can be compared line by line, and neither side took them from the other's
  source.

## What was not taken

- No file under `src/` came from linkwarden.
- No test came from linkwarden's own suite; the rebuild's checks were written against
  SPEC-001 and its question log.
- The database schema is not reused: the rebuild keeps its own records, whose fields are the
  ones §2.2 of the specification names.

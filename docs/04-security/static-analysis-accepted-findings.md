# Static-analysis findings accepted as safe — Clavaris

🟡 En revisión (2026-10-27)

SonarCloud reports some findings that no code change resolves: a Security Hotspot needs a person to
mark it **Safe** in the dashboard, and a handful of CSS `!important` declarations are the standard
idiom for what they do. This page records each decision and its reason in the repository, so the
dashboard comment can point here and a reviewer can check the claim against the code. Review a row
again if the code it names changes.

How to use it: open the finding in SonarCloud, mark it *Safe* / *Won't fix*, and paste the row's
**Reason** as the comment.

## Security Hotspots

| Finding | Where | Decision | Reason |
|---|---|---|---|
| Disabling CSRF protection (`java:S4502`) | `AdminApiSecurityConfig`, the `/api/v1/admin/**` chain | Safe | A resource-server chain with `SessionCreationPolicy.STATELESS`: every request authenticates with a Bearer token in the `Authorization` header, never with a cookie, so there is no ambient credential for a cross-site request to ride on. The chain's `securityMatcher` limits this to the management API. |
| Disabling CSRF protection (`java:S4502`) | `PlatformAuthorizationServerConfig`, the platform `/oauth2/**` chain | Safe | The token endpoint authenticates the client with `client_secret_basic` (an Argon2-hashed secret) and the chain is stateless, so no session cookie exists to forge a request with. This is how every `client_credentials` token endpoint works. |
| Upload size limit above 8 MB (`multipart.max-file-size: 11MB`) | `application.yml` | Safe | Sized for the one feature that needs it: profile pictures accepted up to 10 MB plus the multipart envelope (ADR-0026). The limit is enforced twice more before the bytes are stored — per-Account upload rate limits (`account-picture:upload` and the platform equivalents) and the picture validator's own size and type checks. No other endpoint accepts a file. |

## CSS `!important`

| Finding | Where | Decision | Reason |
|---|---|---|---|
| Remove `!important` | `clavaris.css`, the `prefers-reduced-motion` block | Won't fix | The user's reduced-motion preference must beat every author animation and transition on every element. That requires `!important`; raising specificity cannot out-rank an arbitrary future rule, which is the point of the idiom (it is the pattern recommended by MDN and WCAG 2.3.3 guidance). |
| Remove `!important` | `consumer.css`, `[hidden] { display: none !important }` | Won't fix | Component classes set `display` (flex, grid, inline-block), which silently overrides the browser's built-in `[hidden]` rule, so an element hidden on purpose stayed visible (the "already signing in from another tab" notice showed permanently). `!important` restores the attribute's meaning without touching every component. |

The one `!important` that *was* a specificity conflict, on the profile header's chips, was fixed
by raising the selector instead (TD-FUT-048 follow-up in the P4 branch).

## Not here

A finding that a code change can fix is fixed, not listed here. A decision that depends on a value
this repository does not own (the `security.txt` contact, for instance) is an operator setting, not
an accepted risk.

# ADR-0042: Which API-only controls the dashboard now carries, and which stay API-only

**Status:** ✅ Approved (2026-11-04)

## Context

Adding the username switch to the dashboard (it had been settable only through the admin API since
ADR-0032) raised the question of how many controls were in the same state. Comparing the 53 routes of
the admin API with what the dashboard offers found a group of them with real logic behind them and no
control in front: nine authentication-policy switches, the approval queue for held sign-ups, the reset
of an Account's authenticator app, the Organization's email and domain lists, and a client's sign-in
appearance. In two cases the dashboard even pointed at something it could not show: the Users page
carried an "ignore access restrictions" box for lists nobody could see, and the approval switch had no
screen to approve from.

## Decision

The dashboard carries these, each calling the same use case the API calls, so the audit entry, the
rules and the outbox event are written once:

| Control | Where | Notes |
|---|---|---|
| The nine remaining authentication-policy switches (email verification and its method, email link, new-device check, emailed code after the password, password / username / approval at sign-up, an Account's own email change) | Configure → Sign-in options, *Account access* card | One form for the whole page: what is shown is what is saved, nothing is copied from the stored policy. A rule error keeps what the operator typed. |
| The approval queue | Users → *Pending approval* | Oldest first, 50 at a time with the total, a partial index on pending rows. Approve, or reject with an optional reason cut at 500 characters. Shown while the Organization holds sign-ups or any is waiting. |
| Remove an Account's authenticator app | The Account's page, *Authenticator app* card | Confirmed; offered whenever the Account has one, even if the Organization has since switched the app off. |
| The Organization's blocklist and allowlist | Configure → Restrictions | An entry must be an email address or `@domain`; the page says that an allowlist, once it has an entry, is the only list that counts. |
| A client's sign-in appearance (name, logo, colour) | The OAuth client's page, Configuration | What the domain refuses is shown on the page, not as a 400. |

**Stays API-only, on purpose:**

- **Rotating or revoking a platform client.** It is the credential that can create every tenant. Its
  management over HTTP is already the documented exception in the threat model, and putting it behind a
  dashboard session would make a stolen operator session reach it.
- **Purging a signing key.** It invalidates every token signed under that key at once, and the
  dashboard code says so where it is deliberately absent.

**Not done yet:** a client's custom domain (TD-FUT-067). It is a flow, not a form: a mode, a hostname,
an embedding origin and a DNS TXT record to verify, each with states to show. It needs its own design.

## Consequences

- An Organization that holds sign-ups for approval can now run that from the dashboard alone.
- The Sign-in options page is the one place for every authentication switch. Because the whole page
  is submitted, a form post that omits a checkbox turns it off, as it already did for the earlier
  switches, so the page always renders all of them.
- A new pending-approval index and no other schema change.

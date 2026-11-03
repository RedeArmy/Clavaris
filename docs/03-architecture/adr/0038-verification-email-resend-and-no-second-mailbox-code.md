# ADR-0038: Asking for another verification email, and no second mailbox code after an emailed proof

**Status:** ✅ Approved (2026-11-03)

## Context

Two defects found by the ninth whole-repository review, both about an email sent to the same mailbox.

1. **A person could not ask for another verification email (TD-FUT-055).** The message was sent once,
   at sign-up (`RequestEmailVerificationCommand` is built only by the registration controllers).
   Where an Organization requires a verified address at sign-in (BR-ID-17), a person who never got it
   or lost it was refused at every sign-in, the page said "Didn't get it? Check your spam folder" and
   offered no action, and a password reset does not verify the address. Only an operator could help.
2. **A second code followed an emailed one (TD-ARCH-044).** The new-device challenge (BR-ID-21,
   ADR-0024 §6) applied to every primary factor, including an emailed code or link. With ADR-0037
   making the code the front door, a new device got a sign-in code and then, after typing it, a device
   code: two emails to the same mailbox for one sign-in, the second proving nothing the first had not.

## Decision

### 1. Resend the verification email

- **A page and an endpoint**, `GET`/`POST /o/{organizationId}/verify-email/resend`, reached from the
  "check your email" page, from the sign-in that was refused for an unverified address, and from the
  page that takes the code, each carrying the address so the field is filled in. The field is named
  `email` as every sign-in field is (ADR-0032) and takes a username where the Organization allows it.
- **`ResendEmailVerificationService`** (use case `resendemailverification`) decides whether to send and
  delegates the sending to `RequestEmailVerificationService`, so the policy's method (link, code or
  both) and the test-address bypass are not written twice. It sends only to an **active** Account whose
  address is **not verified**: a verified Account has nothing to be sent, and a suspended, banned,
  deleted, rejected or pending Account must not be handed a way back in by mail.
- **Latest-only and the wait.** The earlier link and code stop working the moment a new message is on
  its way, and another request inside 30 seconds of the last one sends nothing and leaves the earlier
  message valid (`ResendCooldown`), as every other emailed secret here already does (ADR-0033,
  ADR-0034).
- **One answer.** An unknown address, a malformed value, a verified or inactive Account, a request
  inside the wait and a real send all redirect to the same page with the same words, and the
  account-dependent work runs on the bounded email pool after the caller has been answered, so neither
  the page nor its timing says anything about any Account.
- **Limits.** The POST is limited per submitted address and per source IP, over ten minutes, with the
  same numbers as the other requests that send an email.

### 2. No second code after an emailed proof

`DeviceTrustGate` no longer challenges a sign-in whose primary factor was an emailed code or link
(`PendingAuthenticationFactor.ONE_TIME_EMAIL_PROOF`). The challenge is a code mailed to the mailbox, and
the sign-in just proved the mailbox, so whoever passes the first passes the second: no assurance is
added, and the person pays an extra email. A password, a social provider and a passkey are different
from the mailbox and keep the challenge. The per-account bypass (ADR-0026) is unchanged. The device is
still recorded after the sign-in and the "new device" notice (BR-ID-14) still goes out, so the person
is told.

The two controllers lose the branch that handled a failure to send the second code, which can no
longer happen for that factor.

## Consequences

- A person stranded by an unverified address can now get out on their own. The Organization's
  verification policy is not changed by this.
- A request inside the wait gives the person the same "we've sent it" page and no new message; the page
  says the one they already have still works, because saying more would reveal that an Account exists.
- A device authorised by an emailed code becomes a recognised one for later password sign-ins from it.
  An attacker who holds the mailbox could already use the code on any device, so this is not a new
  capability.
- The platform operator tier has its own verification flow and is not covered; it has no stranded-user
  problem of the same size (a handful of operators, an operator who can verify them).

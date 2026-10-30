# ADR-0035: An emailed code after a valid password, on every sign-in, as an Organization option

**Status:** ✅ Approved (2026-10-29)

## Context

ADR-0024 §6 can pause a password sign-in behind an emailed one-time code, but only from a device the
account has not been seen on (`deviceTrustEnabled`). An Organization that wants the code on **every**
password sign-in, whatever the device, has no way to ask for it: a recognised device goes straight
through. Passkeys (WebAuthn, TD-FUT-034) are a separate, stronger option that already exists and is not
what this decision is about.

SP 800-63B-4 §3.1.3.1 does not accept email as an out-of-band authenticator. A code sent to the same
mailbox that resets the password therefore proves control of the mailbox, not a second independent
factor. This option is offered as an extra check against a stolen or guessed password, not as MFA, and
is named and documented that way.

## Decision

- A per-Organization option `codeAfterPasswordRequired` on `AccountAuthenticationPolicy`, **off by
  default**, set through the existing management API (`PUT .../authentication-policy`, field optional,
  absent means off). It is stored beside `selfServiceEmailChangeEnabled`, outside
  `AccountAuthenticationSettings`, for the same reason: it was added after that record was settled.
- When on, a **password** sign-in (email or username) that passes is paused behind the same challenge
  page and the same `DEVICE_TRUST_CHALLENGE` token the new-device case uses, before any session
  exists. The device being recognised does not skip it. If both this option and `deviceTrustEnabled`
  apply, one code is asked, not two.
- It is **not** asked of an email-code, email-link, social or passkey sign-in: each is already its own
  proof, and asking for an emailed code straight after the emailed code just spent would be the same
  factor twice. The per-Account `bypassesDeviceTrust` override (ADR-0026) lifts it, as it lifts the
  new-device challenge.
- The code is the ADR-0034 code, valid 10 minutes, single use, and protected by the ADR-0033 lockout
  (the same per-account counter as the device challenge). Three things the new-device path did not do
  and now does, because this path runs on every sign-in: a new challenge ends the earlier one; a
  request inside the 30-second wait sends nothing new and leaves the earlier code valid; and while the
  method is paused nothing is mailed.
- The email is the ordinary sign-in code, not the "new device" alert, and the page says "enter your
  sign-in code", so neither claims the device is unfamiliar.
- A session completed this way carries the same amr composition as a device-trust step-up
  (TD-SEC-048).

## Consequences

- Every password sign-in on an Organization with the option costs one email, and is bounded by the
  existing per-account and per-IP login limits (BR-ID-06) and the 30-second wait.
- Its security value is that of mailbox control. An attacker with the mailbox (the same one that
  resets the password) is not stopped; a stuffed or guessed password alone is.
- No new token type, table or page. One column on `account_authentication_policies`.

## Alternatives considered

- **A TOTP authenticator app** (a real second factor): the right answer for MFA, and a larger
  decision (enrolment, recovery codes, a secret store). Not this ADR.
- **Making `deviceTrustEnabled` an enum** (off / new device / every sign-in): cleaner in the long run,
  but it changes an existing field's meaning in the management API. A second boolean breaks nothing.

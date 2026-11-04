# ADR-0039: A passkey, offered after an emailed sign-in, is the trusted browser

**Status:** ✅ Approved (2026-11-03)

## Context

TD-FUT-058. With the code leading the sign-in (ADR-0037) an Organization emails a code on every
sign-in. Most providers let a recognised browser skip that step. The obvious mechanism, a "remember
this browser" cookie that skips the code, makes the cookie a bearer credential that stands in for
authentication: whoever copies it signs in without the mailbox. The register therefore left it open
until the mechanism was chosen (options: leave as is; a signed, device-bound, revocable cookie; a
passkey).

## Decision

**A passkey is the trusted browser.** It is a real credential held by the device (TD-FUT-034), it
cannot be copied the way a cookie can, and passkey sign-in already exists and is already one tap. What
was missing was telling the person. After a sign-in that needed an emailed code or link, the person is
offered a passkey for this device.

- **Where.** `EmailCodeSignInController` and `EmailLinkSignInController`, after the session is
  established and the device recorded. The offer is the page `GET /o/{organizationId}/account/passkey-offer`,
  placed beside `/account/passkeys` so the existing `webauthn-register.js` and registration endpoints
  are reused unchanged. It is not shown after a password, social or passkey sign-in, nor after a
  sign-in resumed from a device or session-task challenge: the first has no code to avoid, the others
  are rare and already interrupted.
- **When.** Only to an account with no passkey, and only in a browser that has not said "don't ask
  again". Once a passkey exists the offer never appears, so the common case costs one extra page, once.
- **The destination is server-side.** The URL the person was going to is parked in the session
  (`PasskeyOfferPendingState`) and read back by "Not now", "Don't ask again" and the reload after
  registering. Nothing on the request names it, so the offer cannot be turned into an open redirect,
  and it is removed on use so it cannot be replayed.
- **"Don't ask again"** sets `clavaris_passkey_offer_{organizationId}`, HttpOnly, `SameSite=Lax`, 180
  days. It is a preference: forging or clearing it only hides or shows a page, grants nothing and
  skips no check. That is the difference from the cookie this ADR declines to build.
- **No new rule for the sign-in itself.** A passkey sign-in on the device that registered it is a
  recognised device, so BR-ID-21 asks for nothing more; on another device it keeps asking, as for
  every factor other than an emailed one (ADR-0038).

## Consequences

- The code-led complaint has an answer that adds no bearer credential and no migration.
- No per-Organization switch at first; ADR-0041 added `passkeyOfferEnabled` (and `passkeysEnabled`)
  so an operator can turn the offer off for all its Accounts.
- A browser that does not support passkeys sees the page's notice and the two ways out, and is asked
  again next time unless it chooses "don't ask again".

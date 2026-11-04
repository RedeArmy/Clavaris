# ADR-0041: Organization switches for passkeys, and an audit of which switches exist and which are missing

**Status:** ✅ Approved (2026-11-04)

## Context

ADR-0039 offers a passkey after an emailed-code or link sign-in and recorded, as a known gap, that an
operator cannot turn the offer off. Looking for that switch showed a second one missing: passkey
sign-in and registration (TD-FUT-034) have no Organization switch at all. `login.html` said so in a
comment, "feature detection, not a server-side policy flag". Every other authentication method does
have one, so the gap was an accident of the order things were built, not a decision.

This ADR adds the two switches and writes down the audit that found them, so the next switch is
chosen by a rule and not by whoever notices.

## Decision

### 1. Two Organization switches, both ON by default

`account_authentication_policies.passkeys_enabled` and `passkey_offer_enabled`, default `true`, so no
existing Organization changes behaviour. They are fields of the authentication policy, set through
the policy API (`passkeysEnabled`, `passkeyOfferEnabled`; absent means on, as a client written before
them expects) and on the Organization's *Configure → Sign-in options* page.

- **`passkeysEnabled` off:** the sign-in page does not render the passkey button; both steps of
  passkey sign-in (`/login/webauthn/start` and `/finish`) answer 403; both steps of registration
  answer 403; the account page lists and removes the passkeys that exist but offers no way to add
  one; the profile page does not link to it; the offer never shows. Passkeys already registered are
  kept, so switching it back on restores them. They are never deleted by a switch.
- **`passkeyOfferEnabled` off:** passkeys work as before, but the page that follows an emailed-code
  or link sign-in is never shown, and a visit to it carries straight on to where the person was
  going. It has no effect while `passkeysEnabled` is off.
- Both reach sign-in through the policy snapshot, so a change takes effect within the policy cache
  (30 seconds by default).

### 2. The rule for adding a switch

A feature gets an Organization switch when all of these hold:

1. It changes what an end user sees or can do in the consuming application's own product.
2. Consuming applications plausibly differ on it.
3. Off is a legitimate, safe state: turning it off removes a capability, never a protection the
   operator cannot see, and never deletes data.
4. The default preserves what the system does today.

A feature does **not** get one when off would silently weaken everyone: session revocation, the
anti-abuse rate-limit floor, CSRF, token rotation and reuse detection, the breach check, audit
logging. Those are the security posture, not product options.

A per-**Account** switch (`AccountPermissions`, ADR-0026) is for an operator to give one Account an
exception to a policy the Organization keeps on, and is never a self-toggle.

### 3. The audit

**Organization switches that exist** (all in the authentication, social, session, rate-limit,
redirect and branding policies):

| Capability | Switch | Where |
|---|---|---|
| Verified email required at sign-in, and how it is verified | `emailVerificationRequiredAtSignIn`, `emailVerificationMethod` | Authentication policy |
| One-time email code, email link | `emailCodeSignInEnabled`, `emailLinkSignInEnabled` | Authentication policy, Sign-in options (code) |
| Username at sign-up and as a sign-in identifier | `usernameRequired`, `usernameSignInEnabled` | Authentication policy; `usernameSignInEnabled` also on Sign-in options |
| Password at sign-up | `passwordAtSignUpEnabled` | Authentication policy |
| New-device challenge | `deviceTrustEnabled` | Authentication policy |
| Self-registration needs approval | `selfRegistrationRequiresApproval` | Authentication policy |
| Self-service email change | `selfServiceEmailChangeEnabled` | Authentication policy |
| Emailed code after every password | `codeAfterPasswordRequired` | Authentication policy |
| Authenticator app | `totpEnabled` (ADR-0040) | Authentication policy, Sign-in options |
| **Passkeys** | **`passkeysEnabled` (this ADR)** | Authentication policy, Sign-in options |
| **Passkey offer** | **`passkeyOfferEnabled` (this ADR)** | Authentication policy, Sign-in options |
| Social login and which providers | `socialLoginEnabled`, `allowedSocialProviders` | Connections |
| Multiple sessions per Account, lifetimes | `multiSessionHandlingEnabled`, lifetime and idle timeouts | Session policy |
| Sign-in and token request limits | per-Organization capacity ceiling | Rate limit policy |

**Account switches that exist:** `canDeleteOwnAccount` (gates the self-service "Delete account") and
`bypassesDeviceTrust` (a known-safe Account skips the device challenge). No new per-Account switch is
added: a bypass for the authenticator app or for passkeys would weaken a control the Organization
chose, and the operator's way to help one Account is the reset (`:reset-totp`), which is audited.

**Self-service features with no switch, judged against the rule:**

| Feature | Verdict | Why |
|---|---|---|
| Edit own profile (names, username, phone) | **Add** (TD-FUT-063) | A consuming application that owns that data elsewhere needs the page read-only. Meets all four. |
| Profile picture upload and removal | **Add** (TD-FUT-064) | Needs object storage and moderation an application may not want. Meets all four. |
| Organization default for self-service account deletion | **Add** (TD-FUT-065) | Today only per Account; an Organization-wide default is how it is used elsewhere. |
| Per-Account override of the email-change switch | Later (TD-FUT-066) | Low demand; the Organization switch covers the common case. |
| Change own password | Reject | It is how a person ends a compromise. Off would lock people into a password they cannot change. |
| Sessions page (list and revoke) | Reject | A security control the person needs; off weakens everyone. |
| Connected accounts page | Covered | Governed by the social-login policy already. |
| Login activity (heatmap) | Reject | Read-only, no capability to remove. |

## Consequences

- The passkey gap closed in ADR-0039 is closed, and passkey sign-in is no longer the one method an
  operator cannot turn off.
- Switching passkeys off does not remove the passkeys already registered, so an Account keeps them
  and can remove them; the sign-in just stops honouring them. Removing them is the Account's own
  action on its passkeys page; an operator-side removal for tenant Accounts does not exist yet
  (only the platform dashboard has one, for operators' own passkeys).
- The audit is the reference for the three rows it adds to the debt register; each of them is a
  field in the same policy and follows the same plumbing, so none needs a new design.

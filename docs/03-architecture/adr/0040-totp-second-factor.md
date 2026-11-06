# ADR-0040: An authenticator app as an optional second factor

**Status:** ✅ Approved (2026-11-04)

## Context

TD-FUT-056. ADR-0035 and ADR-0037 both record that a code mailed to the address that resets the
password is not an independent factor (SP 800-63B-4 §3.1.3.1), and the `amr` claim has said `otp`
and never `mfa` for that reason. Passkeys exist (TD-FUT-034) but are per-account and cannot be asked
for. An Organization or a relying party that needs an assurance above "password or mailbox" had
nothing to ask for.

The product owner decided that the second factor is **optional, not mandatory, and controlled by a
switch per Organization**.

## Decision

### 1. The switch

`account_authentication_policies.totp_enabled`, off by default, set through the existing
authentication-policy API (`totpEnabled`) and the Organization's *Sign-in options* page. It means
only that the Accounts of the Organization **may** add an authenticator app. It never forces one.

- **On:** an Account sees *Authenticator app* in its profile and can add one. An Account that has
  added one is asked for its code at sign-in.
- **Off:** nobody is asked and nobody can add one. Nothing is deleted, so switching it back on asks
  the same Accounts again. Because the policy is cached for 30 seconds
  (`clavaris.auth.policy-cache-ttl-seconds`), a change reaches sign-in within that time.

A sign-in needs the factor only when the switch is on **and** the Account has a *confirmed* app
(`RequiresSecondFactorUseCase`). An Account that never added one is never asked, and an abandoned
setup is never asked for.

### 2. The factor

RFC 6238 TOTP over RFC 4226 HOTP: HMAC-SHA1, six digits, 30-second steps, the parameters every
authenticator app implements by default. One step either side is forgiven for clock drift. The
secret is 160 bits.

- **At rest:** AES-256-GCM (`AesGcmTotpSecretCipher`), under its own `TOTP_SECRET_ENCRYPTION_KEY`
  beside the webhook and social-credential keys, refused if it is still the public development
  default (`DevDefaultSecretsStartupGuard`). The server has to read the secret back to check a code,
  so unlike a password it cannot be hashed. Since TD-SEC-086 the stored value is `v1.<keyId>.…`, bound
  to its Account as associated data (a secret copied into another Account's row does not decrypt), and
  the key can be replaced without making enrolled apps unreadable by keeping the old one as a previous
  key (`deployment-runbook.md` §6h). Existing values are re-encrypted under the active key by `TotpSecretReencryptionJob` (TD-SEC-089).
- **Replay:** a code is accepted only for a time step later than the last one spent, and the check
  and the write are one SQL statement, so two requests carrying the same code cannot both succeed.
- **Guessing:** six digits are a million guesses, so wrong entries are counted per Account and the
  method is paused after the limit, the owner told (ADR-0033, `CodeLockout`). The count is cleared
  by a right code and by an operator reset.

### 3. Recovery codes

Ten single-use codes, shown once when the app is confirmed. Each is 16 base32 characters (80 bits)
shown in groups of four. That much entropy is why a plain SHA-256 is enough to store: a leaked table
cannot be searched offline, and a code can be looked up by its hash. A code is spent in one
statement, like a step. Asking for new codes ends the old set.

### 4. Where it is asked

`TotpGate` runs as the **last** pause, immediately before the session would exist, after the primary
factor, device trust and any session task. It follows every primary factor except a passkey: a
password, an emailed code or link, and a social login, because none of them proves what the app
does. A passkey already needs the device's own screen lock or biometric, so asking a code on top adds
friction without a second kind of evidence.

A resumed pause (a device-trust challenge, a session task) hands over to the code page through the
common `completeFromPendingChallenge`, so no pause can be completed around it.

### 5. Fail closed

`SpringSecurityAuthenticatedSessionEstablisher`, the one place every sign-in funnels through,
enforces the factor itself. A correct code leaves a one-shot marker in the session
(`SecondFactorSessionMarker`); the establisher consumes it, adds `AMR_OTP` and `AMR_MFA`, and the ID
token carries `amr: [..., otp, mfa]` and `acr: urn:clavaris:loa:3`. An Account that needs the factor
and has no marker gets **no session** (`SecondFactorRequiredException`). A call site that forgot the
gate therefore fails with an error instead of skipping the factor. A passkey and the restore of a
session that already passed are exempt.

### 6. Managing it

- **Self service**, `/o/{organizationId}/account/two-factor`: add (shows the key and an
  `otpauth://` link), confirm with a code (shows the recovery codes once, in the answer to a POST,
  never on a GET), make new recovery codes, remove. Removing and making new codes ask for a current
  code, and every change asks for a recently confirmed sign-in.
- **Operator**, `POST /api/v1/admin/accounts/{id}:reset-totp`, its own scope
  `platform:accounts:totp-reset`, operator-only, audited with the calling client. It is the way back
  for an Account that lost both the app and the codes.

## Consequences

- `amr: mfa` and `acr: …loa:3` are now truthful, and only for a sign-in that showed the code.
- Optional per Account means the switch alone does not raise the assurance of an Organization whose
  Accounts have not enrolled. A mandatory mode would need an enrolment step in the sign-in itself
  (a session task) and is deliberately not built.
- Left out, and recorded in the debt register: a QR code (the key and a link are shown), mail when
  an app is added or removed, the Organization's name as the issuer in the app (it shows "Clavaris"),
  and an operator button for the reset (the API exists).

**Addendum (2026-11-06, TD-SEC-092).** The authenticator code and the recovery codes are counted and paused apart (`totp-code:` and `totp-recovery:`), and an entry takes its attempt before it is judged, so parallel entries cannot all be judged before the first failure is counted. A right recovery code lifts the pause on the app. Whoever knows the password can still pause the app by guessing; the recovery codes remain the way in.

**Addendum (2026-11-06, TD-SEC-093).** Each pause for the code is numbered and allows 5 entries (`clavaris.totp.max-entries-per-challenge`); when they are spent the sign-in starts again from the password. So one password check can put at most 5 wrong entries onto the Account's own pause, which makes provoking that pause cost one password check per 5 entries. It stays possible by design.

# ADR-0037: The one-time access code leads the sign-in, and is a switch in the Organization's configuration

**Status:** ✅ Approved (2026-11-02)

## Context

ADR-0024 §3 lets an Organization offer sign-in by an emailed one-time code, but only as a link under
the password form (`emailCodeSignInEnabled`), and it had no place in the dashboard: only the admin
API could turn it on. The operator wants it as a choice of how the Organization signs in: a switch in
that Organization's configuration which, when on, makes the hosted sign-in ask only for the email or
username and then the code.

## Decision

- **One switch, one field.** The switch is `emailCodeSignInEnabled`, not a second flag. A near
  duplicate ("offered" versus "leads") would give an operator two settings for one idea. The admin API
  field keeps its name and meaning at the policy level; what changes is what the hosted pages do when it
  is on.
- **Where the operator finds it:** a Configure page, **Sign-in options**, beside Sessions and
  Connections (`PlatformSignInOptionsController`), named "One-time access code" in the dashboard. It
  goes through the same `SetAccountAuthenticationPolicyForOrganizationUseCase` as the API, built from
  the saved policy with only this switch changed, so nothing else an operator set is touched, and the
  rule that an Organization whose sign-up has no password must keep a passwordless method holds (the
  page says so instead of failing).
- **The sign-in leads with it.** With the switch on, `/o/{organizationId}/login` shows only the email
  or, where `usernameSignInEnabled` is on, the username (ADR-0032's rule), and a Continue button. That
  posts to the existing code request, which mails a code and shows the six-box page of ADR-0034. The
  code goes to the account's own email whichever way it was named.
- **The password stays one link away (BR-ID-12).** "Use a password instead" (`?method=password`) shows
  the ordinary form, and a failed password attempt there shows it again. No Organization setting can
  remove the password sign-in, so this does not.
- **An identifier of any shape is answered the same.** The use cases take what was typed
  (`CodeSignInIdentifier`): an email if it has an `@`, otherwise a username where that is allowed,
  otherwise nothing. An unknown address or name, a malformed value, or a username where username
  sign-in is off all redirect to the same code page and mail nobody, so the page reveals no account.
  The wrong codes of one account are counted together whichever way it was named (an account found by
  username is counted under its own email), so a second identifier does not double the guesses; an
  unknown subject is counted and paused like a known one (ADR-0033).
- **Same limits.** The request and confirmation are the existing endpoints, so the per-address and
  per-IP limits, the resend wait, single use, the 30-bit code and the device-trust gate apply unchanged.

## Consequences

- A code-led Organization turns its sign-in into one email per attempt, bounded by the existing limits
  and the 30-second wait.
- The code proves control of the mailbox, as ADR-0035 says; this ADR does not make it a second factor
  (the token's `amr` is `["otp"]`).
- The form field keeps its name, `email`, as ADR-0032 does for the password form: the limits key on it.
  A malformed value is no longer rejected by the page's own validation but answered like an unknown
  one.

## Alternatives considered

- **A per-account preference next to the passkeys.** The login cannot know whose account it is before
  an identifier is typed, so it would have to differ by account and reveal which accounts use the code.
  Rejected for that reason.
- **A separate `accessCodeLoginEnabled` flag.** Rejected for the reason in the first decision.

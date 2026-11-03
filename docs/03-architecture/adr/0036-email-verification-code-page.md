# ADR-0036: A page to type the email verification code, and a way out of the pause

**Status:** ✅ Approved (2026-10-29)

## Context

ADR-0024 §2 lets an Organization verify email addresses with a mailed code (`CODE`, or `BOTH`), but no
page took the code: the only form carried the link's hidden token, and `ConfirmEmailVerificationService`
looked a code up on its own across every pending account, without even reading what people type
(ADR-0034). With 30-bit codes that blind lookup is also the weakest place in the system: a guess only
has to match a code held by *some* pending account.

ADR-0035 left one thing open: where a code is asked after every password, ADR-0033's pause stops the
password sign-in too, and the person could only wait.

## Decision

- **A page, `/o/{organizationId}/verify-email/code`**, asks for the address and the code. The sign-up
  confirmation page links to it (prefilled) when the Organization mails a code, and the mail has a
  button to it. A link-only Organization shows nothing new.
- **A new use case, `confirmemailverificationcode`.** The code is stored under
  `EmailOneTimeCode.hashFor(account, code)` (ADR-0034 addendum) and looked up only for the account the
  address names, so a guess must hit one account's code. Wrong codes are counted per address under their
  own key (ADR-0033); an unknown address and a wrong code give the same answer, and the pause is raised
  for any address alike. Single use is the atomic conditional update. The link path
  (`ConfirmEmailVerificationService`) is unchanged: its 256-bit token needs no account.
- **Limits at the HTTP layer:** 10 requests per 5 minutes per submitted address and 30 per 5 minutes
  per source IP, the same numbers as the sign-in code and the other confirmations.
- **Resetting the password ends the pause on the code asked after a password**, and voids any such
  challenge still waiting. The pause exists because someone who knew the password was guessing; the
  reset removes what they knew. The paused page offers the reset, and the notice mail says so.

## Consequences

- A code emailed before this change cannot be redeemed (it never could: there was no page).
- The reset ends only the pause on the device/after-password code. The pause on a sign-in code or a
  verification code is per address, caused by anyone, and a password does not touch it; it ends by
  itself.

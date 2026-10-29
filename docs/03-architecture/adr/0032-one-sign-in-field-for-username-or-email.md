# ADR-0032: One sign-in field for a username or an email

**Status:** ✅ Approved (2026-10-28)

## Context

ADR-0024 §4 gave username sign-in its own route, `/o/{organizationId}/login/username`
(`UsernameSignInController`), reached from a link on the sign-in page and shown only where the
Organization had turned `usernameSignInEnabled` on. The requirement is a single sign-in form that
takes either a username or an email together with the password.

Reading the code for this change found a defect the separate route had: the per-account and per-IP
login limits (`login:account`, `login:ip`, BR-ID-06) match `POST /o/*/login` exactly, and the default
filter chain only limits the email-code confirmation. `POST /o/*/login/username` therefore had **no**
anti-abuse limit, an unthrottled password-guessing endpoint for every Organization. (The email
passwordless routes were not part of this change.)

## Decision

### 1. One field, routed by its shape

`LoginController` posts to `AuthenticateWithIdentifierUseCase`. An identifier containing `@` is an
email and signs in as `AuthenticateWithPasswordUseCase` always did; one without is a username and
signs in as `AuthenticateWithUsernameUseCase`. `Username` cannot contain `@` and every `Email` has
one, so the choice depends only on the caller's own input and tells nothing about what exists. An
identifier that is neither a valid email nor a valid username is the same generic rejection as a
wrong password; no password check runs for it. Everything else (BR-ID-22's timing equalisation, the
email-verification rule, the anti-enumeration collapsing) stays in the two services.

The form parameter keeps its name, `email`. It is the parameter the login rate limit keys on and
the one every client already posts; a second spelling would have to be honoured by the limit and by
the controller in exactly the same way, or a value could be counted while another was checked. The
label and the HTML type follow the policy instead (below).

### 2. `usernameSignInEnabled` still gates it

A username is accepted only where the Organization has `usernameSignInEnabled` on. The gate lives in
`AuthenticateWithUsernameService`, which did not check it before (only the link on the page was
hidden): a username where it is off pays the same password cost and is rejected like any other
credential. With it on the field reads "Username or email" ("Usuario o correo electrónico") and is a
text input; with it off it stays "Email", an email input, exactly as before.

### 3. New Organizations start with it on

`CreateOrganizationService` and `CreateProductionEnvironmentService` write an explicit policy row
with `usernameSignInEnabled = true` (`AccountAuthenticationSettings.forNewOrganization()`), so a new
Organization accepts both until its tenant turns it off. This is a row, not a change to `defaults()`:
an Organization with no row keeps meaning "the defaults", which is how every already-existing one
behaves, so no existing Organization's sign-in changes.

### 4. The separate route is removed

`UsernameSignInController`, its form, template and link are gone. Leaving them would keep the
unthrottled endpoint. Sign-ins by username now go through `/o/*/login` and its limits.

## Consequences

- **Positive.** One sign-in form; the username sign-in is covered by BR-ID-06's limits for the first
  time (`RateLimitingIntegrationTest`); the gate is enforced in the use case, not only in the markup.
- **Negative.** The two identifiers of one account count against separate per-account limit counters
  (the key is the submitted string), so an attacker who alternates them gets two budgets per account.
  The per-IP limit still bounds the total from one address.
- **Not changed.** Existing Organizations (no row) keep email-only sign-in until they enable the
  switch; the passwordless email routes; the `pwd` authentication method.
- **Supersedes.** ADR-0024 §4's "own dedicated route" decision.

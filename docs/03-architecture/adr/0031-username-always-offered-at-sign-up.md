# ADR-0031: The username is always offered at sign-up, and a social sign-up that ends without one asks for it

**Status:** ✅ Approved (2026-10-28)

## Context

ADR-0024 made the username an optional, per-`Organization` extra: the hosted sign-up form showed the
field only when `usernameSignUpEnabled` was on (off by default), below the email, and a social
sign-up took the provider's name when it was free and silently went without one otherwise.

The product requirement is that every consuming system's sign-up offers a username, above the email,
unique within that system; that a social sign-up takes the username configured on the provider
account; and that the same name can exist in two different systems but not twice in one.

The uniqueness half already holds: `(organization_id, username)` is a partial unique index and
`Username` is lower-cased on construction, so `user1` in System A never blocks `user1` in System B
(BR-ID-20). Everything else is a change to ADR-0024.

## Decision

### 1. The field is always offered, above the email

`register.html` renders the username field in every `Organization`, before the email. Whether it
must be filled in stays the Organization's choice (`usernameRequired`); an empty value is accepted
otherwise. The server already accepted a username whatever the policy said, so only the template
and the model attribute change.

`usernameSignUpEnabled` is removed outright: from the policy, its snapshot, the admin API request
and response, the audit line and the `account_authentication_policies` table (a migration drops the
column). A client written before the removal that still sends the field is accepted and the value
ignored. The cross-field rule "`usernameRequired` or `usernameSignInEnabled` needs
`usernameSignUpEnabled`" (ADR-0024 Decision 1, `UsernameRequiredWithoutSignUpException`) goes with
it: there is no longer a sign-up that cannot produce a username.

### 2. A social sign-up takes the provider's name, with a number when it is taken

`UsernameAllocator` (a pure function in `domain/service`) takes the name the provider offers, a
GitHub login, and returns the first free of `name`, `name2`, `name3`… up to `name20`, cut so that
no candidate exceeds 32 characters. A name `Username` rejects (too short, or characters it does not
allow) is not rewritten: it yields nothing. The availability check is a pre-check; the unique index
stays the backstop, as for the password sign-up.

### 3. A new social account without a username is asked for one before any session exists

Google offers no username, and a GitHub login can be unusable. `AuthenticateWithSocialProviderResult.
LoggedIn` gains `usernameNeeded`, true only for a brand-new sign-up that ended without one. The
success handler passes it to `SessionTaskGate`, which pauses the login (no session, no token) and
redirects to `/o/{organizationId}/login/session-task/username`, a second session task next to the
forced password reset. The person chooses a username (`ChooseAccountUsernameUseCase`, which only
fills an absent one) or, when the Organization does not require one, skips; the session is then
established exactly as the forced reset does.

The two tasks share the session keys, so the pause carries a `TASK` marker and each page serves only
its own: without it, the forced-reset page would accept a username pause and let the person set a
password nobody asked them to set. A pause from before the marker existed has none and is treated as
the forced reset, which is all it could be.

Only a brand-new social sign-up is asked. A returning login, and accounts that already exist
without a username, are not (that would change the sign-in of every existing Organization); they can
choose one in their profile. The cost of that scoping is named: someone who closes the tab on the
choose-username page has an account without a username and, next time, signs in as usual, even in an
Organization with `usernameRequired`.

## Consequences

- **Positive.** Every Organization's sign-up offers a username with no configuration; a social
  sign-up almost always ends with one; the cross-Organization uniqueness rule is proven end to end
  (`SocialLoginIntegrationTest`).
- **Negative.** The admin API loses a field (old clients that send it are unaffected). The username
  of a social account can differ from the one the person has on the provider (`octocat2`).
  `usernameRequired` is enforced at sign-up time, not at every sign-in.
- **Not changed.** Username sign-in, the unique index, `Username`'s shape (3-32 letters, digits,
  underscore, hyphen), the profile page's "set once, then view-only" rule.

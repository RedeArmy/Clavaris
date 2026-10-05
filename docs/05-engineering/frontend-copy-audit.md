# Frontend Copy Audit and Style Guide — Clavaris

🟡 En revisión

| | |
|---|---|
| **Owner** | Engineering (solo project) |
| **Scope** | Every user-visible string: 100 Thymeleaf templates, 23 scripts, server-side validation messages |
| **Method** | Every string extracted by a script (text nodes, `title`/`alt`/`placeholder`/`aria-label`/`data-confirm*`, string literals in `th:*` attributes and in scripts) and read in full; spelling checked against an English dictionary; template structure linted (accessibility, CSP, headings); rendered responses inspected |

## 1. Findings

### Fixed

| # | Finding | Where |
|---|---|---|
| C1 | A Spanish string in an English UI ("Cerrar sesión") | tenant sessions table |
| C2 | Internal references shown to users: architecture decision numbers, business-rule codes, section marks, a competitor's name | 13 visible texts across OAuth Clients, Secret Keys, Signing Keys, API Keys, Rate Limit, Workspace Roles, Webhooks, User metadata |
| C3 | Developer-only phrasing in user-facing sentences: "consuming application(s)", "tenant issuer", "ordinary retry engine", "dispatcher tick", "noisy-neighbor guard", "networkless", "Mints a raw Bearer token", "a hard delete, not reversible" | same pages |
| C4 | Self-contradicting or redundant copy: "A permanent, irreversible action … This is permanent and cannot be undone" | the three delete-confirmation pages |
| C5 | The `(s)` plural hack ("3 scope(s)") | Secret Keys list |
| C6 | Mixed casing for the same label: "Without Team" and "All teams"; "All Events"; "+ New Role"; "+ Add Endpoint"; "Successful Attempts"; "Recent Activity"; page headings "Your Active Sessions", "Your Passkeys", "Update Profile", "Manage Account" next to sentence-case titles | various |
| C7 | Two verbs for one action: "Modify" and "Edit" | Workspace roles |
| C8 | Two phrasings for the same state: "Back to login" and "Back to sign in"; "(this device)" and a "This device" badge; "devices/browsers" | sessions, forgot password |
| C9 | Confusing instructions that described the UI's internals ("the field that replaced the Delete button", "this page's own Remove button", "assign a different substitute") | Workspace and team pages |
| C10 | The profile-picture control was labelled "Update profile" and "Remove photo" | tenant self-service profile |
| C11 | Two phrasings of one validation message ("must not exceed" and "must be at most"); a hyphen used as a dash in one server message | server validation |
| C12 | Audit Log labels capitalised differently from the nouns used everywhere else ("OAuth client" next to "OAuth Clients"; "Secret Key secret rotated") | Audit Log |
| C13 | Page source shipped every design note: Thymeleaf copied HTML comments to the browser, so operator pages carried architecture references and rationale to anyone viewing source, and added them to every response | all templates |

C13 is fixed at the source of the problem rather than by editing comments: `HtmlCommentStrippingDialect` drops comments while a template renders. Conditional comments and comments starting with `!` are kept on purpose.

### Verified clean

- Spelling: no misspelled English word in any visible string (the remaining dictionary misses are product and protocol terms: JWKS, OIDC, PEM, passkey, webhook, and so on).
- Structure (100 templates): every image has `alt`, every button has a `type`, every table header has `scope`, no inline style or script handler, no duplicate id, no heading level skipped, every full page declares a language. The six pages without an `h1` get it from a shared fragment.

### Not changed (needs a product decision)

- **Country list.** Names follow common English usage (for example "Czech Republic", "Ivory Coast", "Turkey"). A few territories with their own dial codes are missing (Faroe Islands, Kosovo, Réunion, Curaçao, Falkland Islands, Jersey, Guernsey, Isle of Man).
- **"Account" and "user".** Operator pages use both ("this Account", "this user", "this account"). The domain term is Account; the UI mixes it with the friendlier "user".
- **Concept nouns in running text.** "Organization" and "Workspace" are capitalised in prose and lower-case in some action labels ("Create workspace"). The rule below documents the current convention; a mass rename was left alone.
- **Developer vocabulary** that is correct for the audience stays: JWKS, OIDC discovery, redirect URI, client_credentials, PEM.

## 2. Copy conventions

Written down so new screens follow them.

1. **Language.** US English ("Organization", "recognize", "customize"). Contractions are fine in sentences ("can't", "doesn't").
2. **Headings and navigation.** Names of dashboard areas are Title Case and match the sidebar: Audit Log, API Keys, OAuth Clients, Secret Keys, Signing Keys, Webhook Endpoints, Workspace Roles, Rate Limit, Danger Zone. Pages for end users and operator task pages are sentence case: "Check your email", "Manage account", "Your passkeys".
3. **Buttons, menu items and dialog titles.** Sentence case, a verb first, no trailing full stop: "Create workspace", "Delete user", "Save redirect settings". Add actions that open a form may carry a leading plus: "+ Add redirect URI".
4. **One word per action.** Add / Edit / Remove (takes something out of a list or role) / Delete (destroys it) / Revoke (withdraws access) / Rotate (replaces a secret) / Deactivate and Reactivate (reversible) / Sign out (an end user's session).
5. **Concept nouns in running text.** Organization, Workspace, Secret Key, OAuth Client, Signing Key and Account are capitalised when they name the Clavaris concept; "client", "key" and "user" stay lower-case when generic. Audit Log entries follow the same rule ("OAuth Client created").
6. **Confirmations of destructive actions.** Say what is destroyed, then what cannot be undone, once. Name the exact thing to type.
7. **Never user-visible:** decision-record numbers, business-rule codes, ticket numbers, class or method names, names of other products, and notes about how the page is built. Those belong in a template comment, which is never sent to a browser.
8. **Counts.** Always pluralise properly ("1 scope", "3 scopes"), never "(s)".
9. **Dashes.** An em dash with spaces around it separates clauses; a hyphen is only for compound words and ranges use an en dash (8–128).

## 3. Follow-ups

- Decide the "Account" versus "user" wording and apply it in one pass.
- Complete the country list, ideally from a maintained source.
- Add a build check that fails on a decision-record number or a competitor's name inside visible text (the extraction script used for this audit can become that check).

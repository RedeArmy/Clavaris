# ADR-0005: Argon2id vs. BCrypt for password hashing

**Status:** ✅ Approved

## Context

Password hashing algorithm choice directly determines resistance to offline brute-force in the event of a database compromise. BCrypt is the long-standing default in many frameworks (including Spring Security's historical default), but is no longer the strongest available option.

## Decision

Use **Argon2id** (`Argon2PasswordEncoder`, Spring Security) — the current OWASP Password Storage Cheat Sheet recommendation for new systems, winner of the Password Hashing Competition, resistant to both GPU and side-channel/tradeoff attacks (the "id" variant specifically balances resistance to both).

## Consequences

- **Positive:** stronger resistance to offline cracking than BCrypt at equivalent tuning effort, directly reducing blast radius of a future database compromise.
- **Positive:** actively recommended by current security guidance, not a legacy default kept for compatibility.
- **Negative:** slightly higher CPU/memory cost per hash operation than BCrypt — deliberately tuned, not a concern at this project's login volume.
- **Negative:** less universally supported outside the JVM/Spring ecosystem than BCrypt if this hashing logic were ever needed cross-language — not a real constraint since password verification only happens inside Clavaris itself, never in a consumer application.

## Alternatives considered

- **BCrypt** — rejected: no longer the strongest available option; kept only as the default in many frameworks for legacy-compatibility reasons that don't apply to a system being built from scratch.
- **PBKDF2** — rejected: weaker GPU-resistance than Argon2id at comparable configuration cost.

## Addendum, 2026-10-24: scope — secrets a person chooses, not secrets the server generates

This ADR decides how to hash a secret **a person chooses**: a password has little entropy, so the
hash must make guessing expensive, and Argon2id does. It was also applied, by default, to client
secrets. That was never a considered decision, and it was the wrong tool: the server generates
every client secret with 256 bits from `SecureRandom` (`OAuthClient`, `OrganizationClient`,
`PlatformClient` rotation), so there is nothing to guess, and a stolen hash is as hard to invert
under SHA-256 as under Argon2id. What Argon2id did add was ~60 ms of CPU and 16 MiB on **every**
`/oauth2/token` call, which `load-testing/README.md` measured as that endpoint's real ceiling.

**Decision.** Argon2id stays for everything a person chooses: account passwords, platform account
passwords, and the platform client's *bootstrap* secret (an operator picks it in an environment
variable). A secret the server generated is stored as `{sha256}` plus the unpadded URL-safe Base64
of its SHA-256, and compared in constant time (`HighEntropyClientSecret`). The fast path refuses
any secret shorter than 32 characters, so a chosen secret cannot be routed through it by mistake.
`ClientSecretPasswordEncoder` checks by stored format: `{sha256}` directly, `$argon2...` through the
existing bulkhead, anything else never matches.

**Consequences.** Clients registered before this change keep their Argon2 hash and keep working;
they move to the fast scheme the next time their secret is rotated (a stored hash cannot be
converted, only replaced, since the raw secret is not kept). The platform bootstrap secret stays on
Argon2 until it is rotated once, which a new deployment should do after first start. No salt is
used, deliberately: the input is unique and unguessable.


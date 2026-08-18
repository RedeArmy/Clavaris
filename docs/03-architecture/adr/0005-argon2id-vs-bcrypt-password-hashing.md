# ADR-0005: Argon2id vs. BCrypt for password hashing

**Status:** ✅ Aprobado

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

# ADR-0002: RS256 vs. HS256 for token signing

**Status:** ✅ Aprobado

## Context

Tokens issued by Clavaris must be verifiable by every consuming application independently. With a symmetric algorithm (HS256), the same secret used to sign a token is required to verify it — every consumer would need a copy of that secret, meaning any consumer's compromise (or any consumer's developer with too much access) is a compromise of Clavaris's entire token-issuance trust. This is exactly the multi-verifier scenario a single-consumer monolith (like JobSeeker's own retired ADR-0009) does not have, and Clavaris does, by design.

## Decision

Sign all tokens with **RS256** (asymmetric). Clavaris holds the private signing key; consumers verify using the public key published at `/jwks.json`. No consumer ever needs a shared secret.

## Consequences

- **Positive:** a consumer application never holds signing-capable key material — a compromised consumer cannot forge tokens for other consumers or escalate its own token's claims.
- **Positive:** key rotation with overlap is transparent to consumers — they always fetch the current JWKS, no coordinated secret-rotation across every consumer required.
- **Negative:** RS256 tokens are larger and signature verification is more CPU-expensive than HS256 — accepted; not a meaningful cost at this project's expected token-issuance volume.
- **Negative:** private key custody (storage, backup, rotation ceremony) is a real operational responsibility this project now owns — mitigated by treating `TOKEN_SIGNING_KEY_STORE_PATH` as a reference to externally-managed key material, never committed or logged.

## Alternatives considered

- **HS256** — rejected: appropriate for a single-issuer-single-verifier system, wrong shape for a system whose entire purpose is serving multiple independent verifiers.

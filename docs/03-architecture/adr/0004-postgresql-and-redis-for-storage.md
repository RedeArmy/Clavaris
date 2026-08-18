# ADR-0004: PostgreSQL + Redis for storage

**Status:** ✅ Aprobado

## Context

Clavaris needs durable storage for accounts, organizations, and OAuth clients, plus fast, ephemeral storage for rate-limit counters and session/cache lookups.

## Decision

**PostgreSQL 16** for all durable domain data (accounts, credentials, sessions, refresh tokens, organizations, memberships, OAuth clients, authorization codes). **Redis 7** for rate-limit buckets and session cache. No `pgvector` extension — Clavaris has no embeddings or semantic search need, unlike JobSeeker's matching engine.

## Consequences

- **Positive:** operational familiarity — same database technology as JobSeeker, no new operational skill required for a solo developer.
- **Positive:** Postgres's strong consistency guarantees suit identity data (an account must never be readable in a half-migrated state).
- **Negative:** running two separate stateful services (Postgres + Redis) adds operational surface versus a single-store design — accepted, consistent with the same trade-off JobSeeker already made for the same reason (fast, cheap-to-recompute cache separate from source-of-truth storage).

## Alternatives considered

- **Redis-only (fully in-memory identity store)** — rejected: identity data needs durability guarantees Redis doesn't provide as a primary store without significant extra configuration.
- **A dedicated session store product (e.g. a hosted session service)** — rejected: unnecessary operational dependency at this project's scale; Redis already does this well.

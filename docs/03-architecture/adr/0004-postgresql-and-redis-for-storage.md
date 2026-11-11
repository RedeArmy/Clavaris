# ADR-0004: PostgreSQL + Redis for storage

**Status:** ✅ Approved

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

## Addendum (2026-11-11, TD-SEC-095): two Redis instances

Sessions and idempotency keys stay on the `noeviction` instance, where dropping a key signs a person
out. The abuse counters (rate limiter, code lockout) move to a second instance with `volatile-ttl`,
because their keys come from anonymous input and a flood would otherwise fill the one instance and
make every write fail. This is only safe because every counter key carries a TTL, which
`CountersRedisSeparationTest` asserts against real Redis. Where no second instance is configured
(local, tests) the counters share the sessions' connection. Counters are not a system of record, so
losing some to eviction under flood costs precision, never correctness of an account.

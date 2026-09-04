package com.clavaris.organization.domain.model;

/**
 * SDE-III feature build, 2026-09-04 (Clerk Development/Production instances analysis): whether this
 * {@code Organization} is a sandboxed testing environment or the real, unbounded one a consuming
 * system's real users actually authenticate against. Deliberately a property of {@code
 * Organization} itself, not a new isolation concept — a {@code DEVELOPMENT} Organization gets
 * exactly the same structural isolation (its own issuer, JWKS, account pool, ADR-0010 §5) any other
 * Organization already gets; only the *policy* differs (lower default capacity ceiling,
 * verification emails bypassed), never the isolation guarantee itself.
 *
 * <p>Every {@code Organization} created via {@code Organization#register} defaults to {@code
 * DEVELOPMENT} going forward — the safer default, matching Clerk's own "you get a sandbox first,
 * production is a deliberate later step" posture. Every {@code Organization} that already existed
 * before this concept shipped defaults to {@code PRODUCTION} at the database level (migration
 * {@code V20260904090000}) — those rows are already being used as real, unsandboxed tenants;
 * nothing about their behavior changes retroactively.
 */
public enum OrganizationEnvironment {
  DEVELOPMENT,
  PRODUCTION
}

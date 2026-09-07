package com.clavaris.common.application.port;

import java.util.Optional;
import java.util.function.BooleanSupplier;

/**
 * TD-FUT-017: bounds how many CPU-bound credential-verification calls (Argon2id password/client-
 * secret matching, ADR-0005) run concurrently across this JVM. `load-testing/README.md`'s own
 * measured finding is that Argon2id verification, not Postgres or Redis, is what collapses {@code
 * /oauth2/token}'s latency under concurrency (p95 537ms at concurrency 10, a 14.2s queueing
 * collapse at concurrency 30, on a 3-core machine) — every caller that verifies a password or
 * client secret competes for the same limited CPU budget, so the bound has to be one shared gate,
 * not a per-call-site limit each module would otherwise have to size independently.
 *
 * <p>Deliberately does not, and cannot, reduce the total CPU work Argon2id needs — that would mean
 * weakening its cost parameters, an unvalidated security trade-off this row's own text already
 * declined to make without a fresh load-test run against production hardware (still not available,
 * TD-FUT-013's own host having shipped but not yet carried real traffic). What this bounds instead
 * is the failure *shape*: past the configured concurrency ceiling, an excess caller is told plainly
 * that the system is overloaded (never run at all) rather than being admitted and left to queue
 * behind every other request already competing for the same cores, the exact mechanism that turned
 * one slow endpoint into an unbounded, multi-second latency collapse in the measured benchmark.
 *
 * <p>One generic method, not a `matches`-shaped one — {@link #runBounded} takes a {@link
 * BooleanSupplier} so it never needs to know whether the caller is verifying a password ({@code
 * identity-module}) or an OAuth2 client secret ({@code app}), and returns an {@link Optional}
 * rather than a bare {@code boolean} specifically so a caller can never conflate "the gate refused
 * to even attempt this" with "the credential was attempted and didn't match" — the two outcomes
 * need genuinely different handling (a 503-shaped "try again" vs. a 401-shaped "wrong credential"),
 * and collapsing them into one boolean would silently produce the wrong one.
 */
@FunctionalInterface
public interface CpuBoundVerificationGate {

  /**
   * @param verification the CPU-bound match check to run, e.g. {@code () ->
   *     argon2Encoder.matches(raw, hash)} — never invoked at all if no permit becomes available
   *     within this gate's own configured wait bound.
   * @return {@link Optional#empty()} if the gate was saturated and no permit freed up in time
   *     (verification never ran); otherwise the real result of running {@code verification}.
   */
  Optional<Boolean> runBounded(BooleanSupplier verification);
}

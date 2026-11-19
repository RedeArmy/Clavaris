# ADR-0045: A Redis outage stops neither the start-up nor an account's suspension or deletion

**Status:** 🟡 Pending review (2026-11-18)

Two findings of the JobSeeker second validation round, one decision each. The common rule: **Redis holds no system of record, so its absence degrades readiness and nothing else.** The rate limiter and the lockout already fail open (TD-SEC-022), the cache-invalidation bus retries its subscription (72d0edd6); this ADR finishes the job.

## 1. The application starts with Redis unreachable (TD-ARCH-056)

### Context

Started with Redis stopped, the packaged jar ended with `Application run failed`. The register row named one cause: Spring Session's `EnableRedisKeyspaceNotificationsInitializer` runs `CONFIG SET notify-keyspace-events Egx` while the context starts. A test that starts the whole context against a port nothing listens on found a **second** one the row did not know: Spring Session's own `springSessionRedisMessageListenerContainer` subscribes when the context's lifecycle starts and fails it the same way. Fixing only the first would have moved the failure, not removed it.

### Decision

1. **The application never configures the Redis server.** `ConfigureRedisAction.NO_OP`; the sessions Redis carries `--notify-keyspace-events Egx` itself (both compose files, the runbook, and the test container). Besides the outage, this is also what lets the same build run on a managed Redis that disables `CONFIG`, where it failed with Redis healthy.
2. **The session listener container is started by Clavaris, not by the context.** `TolerantSessionListenerStartup` turns off its auto-start, starts it from a lifecycle hook, and on failure retries every 10 s in the background. Until it succeeds, expiry events are not heard and Spring Session's once-a-minute cleanup removes the leftovers, which is how the store behaved before events existed.
3. **What stays true while Redis is down** (verified by the spike and now by `RedisUnreachableStartupIntegrationTest`): liveness 200, readiness 503 until Redis returns, then 200 without a restart.

### Consequences

- **A Redis you run or buy must have `notify-keyspace-events` containing `Egx`.** Without it, expired and deleted sessions are not announced and their index entries wait for the cleanup. This is a deployment requirement now, written in the runbook (§6e), not something the application papers over at start-up.
- A restart or rolling deploy that lands while Redis restarts no longer takes the identity provider down for every consumer.

## 2. Suspending, banning or deleting an account does not depend on the session store (TD-ARCH-057)

### Context

`AccountRevocationCascade` ends with closing the account's browser sessions, which reads the session index in Redis. With Redis down, `:suspend` and `:delete` answered 500 after a command timeout. The failure was atomic (nothing half-applied), which is the good part, but it hit the path that matters most: a consumer's erasure job (BR-DATA-06) could not tell the 500 from a defect, and a suspension ordered during an incident could not be applied.

### Alternatives

- **Keep it fail-closed and map the error to a 503 with `Retry-After`.** Honest, but an operator facing an incident still cannot suspend, and the cost falls on exactly the moment Redis is least reliable.
- **Apply the change and close the sessions later (chosen).** The change that makes an account unable to act lives in Postgres: the credentials and the refresh tokens are revoked there in the same transaction, so the account cannot sign in or refresh again whatever Redis says. What lingers is an already-open browser session, which cannot be presented while its store is down.

### Decision

1. **`DeferringAccountSessionRevoker` is the `AccountSessionRevoker` every use case gets** (suspension, ban, deletion, password reset, workspace removal, Organization erasure). It tries the store; on a `DataAccessException` (connection failure or command timeout) it records the account in `pending_session_closures`, **in the same transaction as the state change**, and carries on. A failure that is not the store's is not swallowed.
2. **After one failure the store is not tried again for 30 s** (`clavaris.session.store-retry-pause-ms`). Deleting an Organization erases every account, and a command timeout per account would turn an outage into minutes.
3. **`PendingSessionClosureJob` closes them when Redis answers**: every 15 s, oldest first, stopping at the first failure; closing a session twice and removing a row twice are no-ops, so it needs no lock across instances. The window in which a suspended account's old session could still be presented is that interval, counted from Redis coming back.
4. **Observable:** `clavaris.session.closure.deferred` and `.completed`, the log events `session_store_unreachable` and `pending_session_closure_completed`, and the table itself (`select count(*) from pending_session_closures` should be zero outside an outage). No account id appears in a log (BR-DATA-01).

### Consequences

- `:suspend`, `:reactivate` and `:delete` answer as usual during an outage; JobSeeker's erasure job needs no special retry for this.
- Not covered, same shape, separate row if it matters: the platform tier's own account sessions (`PlatformAccountSessionRevoker`), which are operators' dashboard sessions, not a consumer's users.
- A reactivation that arrives before the job runs finds a pending row for an account that is active again; the job still closes that account's old sessions, which is the safe direction (the person signs in again).

## Tests

`RedisUnreachableStartupIntegrationTest` (the whole context against a dead Redis: liveness 200, readiness 503), `TolerantSessionListenerStartupTest`, `DeferringAccountSessionRevokerTest`, `PendingSessionClosureJobTest`, `JpaPendingSessionClosureRepositoryTest` (real `ON CONFLICT`, ordering), and `OrganizationClientErasureIntegrationTest` with the Redis container **paused**: suspend, delete and reactivate all answer 204, both accounts are recorded, and once Redis is unpaused the job empties the table.

## Addendum, 2026-11-20 (TD-SEC-102): deleting an Organization during an outage

The same rule was not met by the one lifecycle operation that closes sessions **after the commit**: deleting an Organization. `OrganizationIdentityDataEraserBridge` returns an action that runs once the commit is done (TD-PERF-043), and there `DeferringAccountSessionRevoker`'s write joined a transaction that had already completed and failed (`InvalidDataAccessApiUsageException`). One failure ended the action before it reached the purge of the Organization's signing-key file, so with Redis down the file, holding private keys, stayed on disk for good and no closure was recorded. ADR-0045's tests did not see it: the account-level paths call the revoker inside their own transaction. A test that deletes an Organization with Redis paused now does, and it failed before the fix.

- **Own transaction.** Each account's closing runs in a `REQUIRES_NEW` transaction opened by the bridge, the one place that knows it is after a commit. The repository keeps joining the caller's transaction, so a suspension still rolls its closure back with it.
- **Independent steps, purge first.** The key purge needs neither Redis nor a database, so it goes first, and a failure in either step, or for one account, no longer skips the others.
- **A sweep for what still escapes.** The kid list lives only in the running action, so a crash between the commit and the purge leaves nothing to retry from. The file is the Organization's own (TD-SEC-054), so `OrphanedSigningKeyFileCleanupJob` (04:10) removes any `signing-keys-org-<id>.p12` whose Organization no longer exists, whole, without the list. A file under an hour old is left alone: a new Organization's file is written before the transaction that creates its row commits. A table of pending purges was not needed.

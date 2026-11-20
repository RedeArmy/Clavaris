# ADR-0049: A per-client access-token lifetime, the operator tier's deferred session closure, and a Redis that says what it is missing

**Status:** 🟡 Pending review (2026-11-21)

Three rows left open by the 13th review: TD-SEC-104, TD-ARCH-063 and TD-ARCH-062.

## 1. The access-token lifetime is a per-client setting (TD-SEC-104, BR-ID-36)

### Context

Every access token lived for Spring Authorization Server's default of five minutes, for every client of every Organization, and nothing could change it. It is also the bound of what revocation cannot do: a token issued before a revocation or a logout stays valid until it expires, because nothing links a Session to the `oauth2_authorization` rows it produced (ADR-0044 §1). A consumer that wants a shorter window for a sensitive client, or a longer one to refresh less, could not choose, and the figure was written down nowhere a consumer would read.

### Decision

1. **`oauth_clients.access_token_ttl_seconds`**, nullable. Null is the default (300), so every client that exists behaves exactly as before, and a client only changes it by a deliberate, audited act of its owner, the same shape as the PKCE policy (ADR-0043).
2. **A hard cap, not a tenant's to move:** 60 to 3,600 seconds, enforced by the domain (`AccessTokenLifetime`, an `InvalidInputException`, so the API answers 400), by the request's bean validation and by a `CHECK` on the column. A minute is the shortest that survives a token request and a retry; an hour is the longest a revocation is allowed to leave a stolen token usable.
3. **Set where the client is registered and changed where its other settings are.** `POST .../clients` takes an optional `accessTokenTtlSeconds` and the response shows the effective value; the dashboard's client page has a row and a dialog (blank returns to the default). Both directions are audited as `oauth_client.access_token_ttl_updated` with what it was and what it became, tone *sensitive* because a longer lifetime widens the window.
4. **It reaches the tokens.** `OrganizationRegisteredClientRepository` sets the registered client's `accessTokenTimeToLive`, which applies to the Authorization Code exchange and to every refresh; the refresh token's own 30 days are untouched. There is no cache in between, so a change applies to the next token.
5. **The larger alternative stays unbuilt:** linking a Session to its authorizations so that revocation reaches access tokens. At five minutes it is not worth it, and a client that cares can now ask for one.

### Consequences

- The window after a revocation is the client's lifetime, between a minute and an hour, and a consumer reads it in the integration guide.
- A token already issued keeps the lifetime it was issued with; a change applies from the next one.

## 2. The operator tier's sessions close later too (TD-ARCH-063, BR-ID-33)

ADR-0045 left suspending a PlatformAccount out: `SuspendPlatformAccountService` is transactional and read the session index in Redis directly, so an operator suspension during an outage was still a 500. The same design now covers it: `DeferringPlatformAccountSessionRevoker` records the account in `pending_platform_session_closures` in the same transaction when the store raises a data-access failure, and `PendingPlatformSessionClosureJob` closes the sessions every 15 seconds once Redis answers. The credentials and refresh tokens are revoked in Postgres either way, so the account cannot sign in; only a dashboard session already open lingers, and it cannot be used while its store is down. A second table and a second job, and not a type column, because the two tiers' ids belong to different tables and the code reads more plainly with two small, parallel pieces than with one that switches.

One thing is shared: **the pause after a failure** (`SessionStoreCircuit`). Redis being down is one fact, so the first caller, tenant or operator, to find out tells the other, and an operator suspension right after a tenant one does not wait for its own command timeout. The existing backlog alert (`SessionClosureBacklog`) reads both gauges.

## 3. Redis says what it is missing (TD-ARCH-062)

ADR-0045 moved `notify-keyspace-events Egx` from a command the application ran at start-up to a setting the server must carry. Nothing checked it, and the two listeners retried in silence.

- **`RedisKeyspaceEventsCheck`** reads the setting at start-up (after 30 s) and every five minutes and sets the gauge `clavaris_redis_keyspace_events_configured` to 1 (the flags are there: `E` and `g` and `x`, or `A`), 0 (Redis answered and they are not) or -1 (it could not be read). `RedisKeyspaceEventsMissing` pages on 0 for ten minutes. **-1 deliberately never alerts**: a managed service that forbids `CONFIG` is exactly the case ADR-0045 made possible to run, and a Redis that is down has its own alerts. It never throws and never blocks.
- **A subscribed gauge per listener** (`clavaris_redis_listener_subscribed{listener}`, 1 or 0) for the session listener and the cache-invalidation listener, next to the failed-attempt counter and its alert from TD-PROC-036.
- **Not in readiness.** The register's row proposed readiness detail for both. Readiness answers "should traffic come to this instance?" and a deaf listener or a missing flag degrades the application without making it unable to serve: sessions linger until the cleanup, caches until their TTL. Taking an instance out of rotation for that would turn a degradation into an outage, and the application already reports Redis itself unreachable through readiness. The state is for the operator, through the metrics and the alerts.

## Tests

`AccessTokenLifetimeTest`, `OAuthClientTest`, `JpaOAuthClientRepositoryTest` (round trip, and the `CHECK` refusing 30 and 7,200), the registration service, controller and dashboard routes, `OrganizationRegisteredClientRepositoryTest` (the token settings) and, over the real Authorization Code exchange and a refresh, `RefreshTokenRotationIntegrationTest` (`expires_in` is the client's lifetime, and 300 for a client that asked for none). For the operator tier: the decorator, the job, the shared circuit, the repository against Postgres and `PlatformAccountSuspensionDuringRedisOutageIntegrationTest` with Redis paused (red without the decorator, with the original `QueryTimeoutException`). For Redis: the check's unit tests (every flag combination, a forbidden `CONFIG`, a Redis that is down), `RedisKeyspaceEventsCheckIntegrationTest` against a real Redis whose flags it removes and restores, the two gauges, and promtool tests for the two alert changes.

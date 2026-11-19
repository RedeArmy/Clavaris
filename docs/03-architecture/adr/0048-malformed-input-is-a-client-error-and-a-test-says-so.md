# ADR-0048: Malformed input to the admin API is a 4xx, and a generated test says so

**Status:** 🟡 Pending review (2026-11-19)

Follows the 13th review (TD-ARCH-060, TD-ARCH-061, TD-TEST-019) and adds the alerts the resilience work of ADR-0045 and ADR-0046 lacked (TD-PROC-036).

## 1. Malformed input is a client error (BR-ADMIN-03)

### Context

Three times in two weeks an admin endpoint answered **500** to a value no client should send: a webhook URL that was not https (TD-ARCH-058), a username, a 5,000-character name or a `null` provider (TD-ARCH-060, TD-ARCH-061). Always the same shape: a domain check throws `IllegalArgumentException`, or a null reaches a collection, the controller does not map it, and `GlobalExceptionHandler` answers `internal_error`. The cost is real if small: error-rate alerts count a mistake as an outage, the caller is told to retry what it must correct, and each defect was found by a consumer's report, one endpoint at a time. 17 of 65 REST controllers catch `IllegalArgumentException` on their own, 19 request bodies reach a use case with no catch.

### Decision

1. **Validate at the boundary.** A request body states what the column and the domain accept (`@Size`, `@Pattern`, `@NotNull` on the element type of a list) and the controller takes it `@Valid`, so the standard handling answers 400 before any use case runs. `UpdateAccountProfileRequest` now bounds the names (255, their column), the username (the pattern `Username` enforces) and the phone number (digits with the usual separators, at most 32, their column); `SetSocialLoginPolicyRequest` and `CreateOrganizationClientRequest` refuse a `null` element.
2. **A typed failure for what the boundary cannot see.** `InvalidInputException` (in `common`) extends `IllegalArgumentException` and is what a domain rule throws for a value a caller sent; `Username` and the webhook URL (`InvalidWebhookUrlException`) are the first. `GlobalExceptionHandler` answers it **400** with `{"error":"invalid_input", correlationId, timestamp}`, and neither logs nor returns the exception's message, which may quote the value (a username). It is a safety net under the validation, not a replacement for it.
3. **Never `IllegalArgumentException` in general.** Mapping every illegal argument to 400 would turn a real defect (a null that should never have got there) into a client error nobody investigates. Only the typed subclass is a client error; an ordinary `IllegalArgumentException` stays a 500 and a test pins that.

### Consequences

- A new domain rule that validates what a client sent throws a subclass of `InvalidInputException`; the existing ones that throw plain `IllegalArgumentException` are converted as they are touched, not in one sweep.
- The phone number is a display string ("+502 5555-0100"), not an E.164 parse: the check keeps nonsense out of the column and does not decide what a valid number is.

## 2. A generated sweep is the gate (TD-TEST-019)

`AdminApiHostileInputIntegrationTest` reads the OpenAPI document the application serves and, for every `POST`, `PUT` and `PATCH` under `/api/v1/admin` that takes a JSON body, sends each field in turn a handful of hostile values (empty, 5,000 characters, the wrong shape, a `null`, a `null` element, an unknown enum constant, a number out of range, a URL with an impossible port) against real resources, and asserts that no answer is a 5xx and none was throttled. It sweeps 24 endpoints with 474 requests in about a minute, and a route added tomorrow is swept tomorrow without anyone adding it to a table. It found, besides the two known, a third (`POST .../secret-keys` with a `null` scope), and without the fixes it fails listing the endpoints. Routes without a body (the `:delete` family) are not swept: there is nothing to malform and real ids would erase the resources the sweep needs. The test fails if it finds fewer than 20 endpoints, so a change to the document cannot make it pass by sweeping nothing.

## 3. Alerts for the resilience signals (TD-PROC-036)

The degraded modes of ADR-0045 and the window of ADR-0046 are only a comfort if someone can see them. Four alerts, each with a promtool test, and the metrics they needed:

| Alert | Reads | Why |
|---|---|---|
| `SessionClosureBacklog` (10 min) | gauge `clavaris_session_closure_pending`, refreshed by the closing job every 15 s | accounts whose browser sessions are still open after a suspension or deletion made during an outage |
| `RedisListenerNotSubscribed` (15 min) | counter `clavaris_redis_listener_start_failed_total{listener}` | the session listener or the cache-invalidation listener cannot subscribe (it retried silently for ever) |
| `OrganizationKeyPurgeFailed` | counter `clavaris_organization_key_purge_failed_total` | the private keys of a deleted Organization may be on disk until the 04:10 sweep |
| `RefreshTokenReuseGracedHigh` | counter `clavaris_auth_refresh_token_reuse_graced_total` | a burst of graced reuse is a client losing responses or a thief racing one; the threshold (20 in ten minutes) is a starting point to tune once a consumer has the window on |

Also counted: `clavaris_signing_key_orphaned_files_removed_total` (a file the nightly sweep had to remove is one a purge missed), for a graph and not an alert.

## Alternatives considered

- **Map every `IllegalArgumentException` to 400.** Simplest, and wrong for the reason in §1.3.
- **A hand-written table of endpoints for the sweep.** It is what found the first two; it misses the third, and a table is exactly what is not updated when a route is added.
- **Alert on logs.** No log pipeline is promised by the deployment; a counter reaches the rule engine that already exists.

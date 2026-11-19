# ADR-0047: A status read for an Organization's Secret Key, and a webhook URL that is checked before it is resolved

**Status:** 🟡 Pending review (2026-11-20)

Three small rows found by the JobSeeker validation rounds, two of one kind and one of another: TD-FUT-081, and TD-ARCH-058 with TD-ARCH-059.

## 1. A status read that spends nothing (TD-FUT-081)

### Context

A consumer that wants to know whether an Account is still allowed had two options: spend its refresh token (which rotates it, with the consequences of TD-SEC-103) or wait for a webhook (which can be lost while the endpoint is disabled and arrives out of order). The only account read an `OrganizationClient` had was `GET .../profile`, which answers names, username and phone but not the status, and returns personal data a status check does not need.

### Decision

1. **`GET /api/v1/admin/accounts/{id}/status`** answers `{"accountId": ..., "status": ...}` and nothing else, so there is no personal data to leak. The values are `ACTIVE`, `SUSPENDED`, `BANNED`, `PENDING_APPROVAL` and `REJECTED`.
2. **`DELETED` is never an answer.** Deletion is physical (BR-DATA-02): an erased Account has no row. For an Organization's Secret Key it is the same **403** as another Organization's Account, an unknown id and a malformed one (the ownership filter of ADR-0044 resolves nothing of its own), so the read is no oracle for "which ids exist elsewhere" or "which ever existed". A consumer that knew an id and now gets 403 should treat it as gone. A platform credential, which carries no Organization, gets an ordinary 404 for an unknown id.
3. **Its own scope, `platform:accounts:read-status`**, opt-in (no existing Secret Key has it) and not operator-only: it tells whoever holds it which of an Organization's accounts are suspended or banned, and a consumer's own backend is exactly who should hold it. This is the scope-per-read precedent `GET .../profile` already sets.
4. **Cheap and uncacheable.** It reads one column (`findStatusById`), not the Account with its credentials, writes nothing and can be repeated freely; the response carries `Cache-Control: no-store`, since a cached status defeats the point of asking.
5. **No "instant of the last change" in this version.** The row proposed one, but an Account does not record when its status changed; adding it means a column, a migration with a backfill and a touch on every transition, for a check whose job is "is this Account allowed now". The body is a record, so a field can be added later without breaking a reader.

### Consequences

- The call is subject to the admin API's per-client rate limit. It is meant for a check at a decision point (a job, a login of a returning user), not for every request a consumer serves; polling it per request would exhaust that limit.
- No dashboard counterpart: the dashboard already shows each Account's status, and this is a read with no control to mirror.

## 2. The shape of a webhook URL is checked before it is resolved (TD-ARCH-058, TD-ARCH-059)

### Context

Registering a webhook endpoint ran the SSRF guard, which resolves the host in DNS, **before** the domain checked the URL's shape. A URL with a scheme other than https therefore reached the domain, which threw an `IllegalArgumentException` nobody caught: a 500 for bad input, counted in the error-rate alerts and telling the client to retry. It also meant a DNS lookup for a URL that was never going to be accepted. Separately, `java.net.URI` parses any run of digits as a port, so `https://example.com:99999/h` was accepted and then failed every delivery until the failure threshold disabled it, an hour per delivery later.

### Decision

1. **Shape first.** `WebhookEndpoint.requireValidUrl` is public and the registration and URL-update use cases call it right after the Organization check, before the lock, the count and the SSRF guard. A bad URL costs nothing and answers at once.
2. **A typed failure.** `InvalidWebhookUrlException` extends `IllegalArgumentException`, so anything expecting that keeps working, and an adapter answers 400 for exactly this case instead of for every illegal argument (which would be a defect hidden as a client error).
3. **The port belongs to the shared validator.** `AbsoluteHttpsUrlValidator` rejects a port outside 1 to 65535 (no port is fine). It is shared with the branding and domain-configuration URLs, which gain the same check, and whose controllers already answer 400.
4. **Four paths, not one.** The REST registration, the dashboard's registration and the dashboard's URL update all had the gap (there is no REST update route). The dashboard shows a form error in its own words; the registration form also used to set the SSRF error without ever rendering it, which is fixed in passing.
5. **A 400 without a body**, like the other 400s of that controller. The "standard error body" the row mentions exists only for the 500; inventing a format for these routes was not worth it.

## Tests

Status read: service, controller (each state, no-store, two fields), JPA repository against Postgres, the ownership-filter route, the scope, and `OrganizationClientErasureIntegrationTest` end to end (every state, no personal data, another Organization's, unknown, erased and malformed ids all 403, no scope 403, platform credential 404). Webhook URL: the validator and domain tables (`http`, `ftp`, `javascript`, no scheme, ports 0, 65536 and 99999 rejected, 1, 443, 65535, none and an empty port accepted), both use cases (the SSRF guard is never called), the REST and dashboard controllers, and the same cases over real HTTP with an Organization's Secret Key.

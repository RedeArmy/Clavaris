# ADR-0007: Webhook-based event delivery to consumer applications

**Status:** 🟡 Propuesta — pendiente de revisión antes de considerarse ✅ Aprobado y añadirse a la lista de ADRs vigentes del proyecto

## Context

Consumers need to *react* to identity/organization events — a new account registered, a session revoked, a membership created, an account deleted — without polling Clavaris continuously and without Clavaris ever writing directly into a consumer's own database or infrastructure. Direct writes to a consumer's data store would break the module boundary this whole system exists to preserve and would silently reintroduce a per-consumer coupling that contradicts "reusable across any project, any language, under a day" (`vision-document.md` §6, ADR-0006).

Clerk (and, before it, Stripe) solved this with a mature, well-understood pattern: consumers register a URL per application, Clerk pushes signed HTTP `POST` events to it, and the consumer's own backend decides what to do with each event on its own infrastructure. This is the pattern being adopted here, adapted to Clavaris's module boundaries.

## Decision

Introduce a new bounded context, **`webhook-module`**, alongside the project's existing four modules. It owns webhook endpoint registration, event subscription, signing, delivery, retry, and delivery history — it does **not** own the identity or organization domain logic that produces the events it delivers.

### 1. Event production — transactional outbox, not a direct publish call

`identity-module` and `organization-module` already raise domain events internally (`domain-model.md` §7). Each event that should also leave the system as a webhook additionally writes one row to a shared `event_outbox` table, **in the same database transaction** as the state change that caused it (e.g. inserting the `Account` row and the outbox row is one commit). This closes the classic dual-write problem: without an outbox, a crash between "account committed" and "event published" silently loses the event, and there is no way to notice.

**Scope boundary — resolved ambiguity (2026-08-17):** this outbox/webhook pipeline is the mechanism for notifying *external consumers*, full stop. It is never the mechanism by which Clavaris enforces its own internal invariants (session revocation on password reset, full-token-family revocation on reuse detection, workspace-membership removal on account deletion). Those cascades are performed synchronously, inside the same use-case transaction, via direct calls to the other module's own port — *before* the corresponding event is raised and written to this outbox (`domain-model.md` §7 has the full rule and the per-event breakdown). This distinction matters because webhook delivery is explicitly best-effort/eventually-consistent at the transport level (see Consequences below) — if an internal security invariant depended on `webhook-module`'s dispatcher successfully delivering something, that invariant would only hold "eventually, maybe," which is not an acceptable property for BR-ID-03/BR-ID-04/BR-DATA-03. `webhook-module` only ever *observes* facts that already happened; it never causes them.

`webhook-module`'s dispatcher polls `event_outbox` for unpublished rows (`SELECT ... FOR UPDATE SKIP LOCKED`, safe for more than one dispatcher instance running concurrently — see NFR concurrency note below), fans each row out to every `WebhookEndpoint` subscribed to that event type, and marks the outbox row published once all fan-out `WebhookDelivery` rows exist. The dispatcher never talks to `identity-module`/`organization-module` directly — it only reads the shared outbox table, respecting the hexagonal dependency rule by depending on a data contract, not on the other modules' internal types.

### 2. Delivery — signed, retried, replayable

- Every `WebhookEndpoint` gets its own signing secret at registration time (never reused across endpoints, never returned again after creation — only its hash is stored, same principle as `oauth_clients.client_secret_hash`).
- Every delivered payload is signed HMAC-SHA256 over `timestamp + "." + raw_body`, sent as a `Clavaris-Signature: t=<timestamp>,v1=<hex_hmac>` header — the same shape Stripe/Svix/Clerk use, chosen deliberately because it's a pattern consumer developers already recognize and existing verification libraries exist for.
- Delivery is **at-least-once**, never at-most-once. Every event carries a stable `event.id` (UUID); consumers are expected to deduplicate on it (BR-WEBHOOK-02).
- Failed deliveries (non-2xx response, timeout) retry with exponential backoff + jitter, capped at a fixed number of attempts over a bounded window (e.g. 8 attempts over 24h — exact schedule is an implementation detail, not re-litigated here). After the final attempt, the delivery is marked `EXHAUSTED`, never silently dropped, and is visible for manual replay via the management API.

### 3. Event catalog is versioned data, not a versioned endpoint

Each webhook payload carries its own `api_version` field (Stripe's approach) independent of the management API's URL-based versioning (ADR-0008) — a payload shape change is versioned per-event-type, so adding a new event type never forces existing subscribers to change anything.

## Consequences

- **Positive:** zero infrastructure required on the consumer's side to integrate — an HTTPS endpoint that can verify an HMAC signature is enough, in any language. Preserves the core reusability claim this project is built around (ADR-0001, ADR-0006).
- **Positive:** the transactional outbox makes event delivery reliable (no lost events on crash) without introducing a message broker as an operational dependency — consistent with `nfr-quality-attributes.md` §6 (solo-developer operability).
- **Positive:** Clavaris never gets network access to, or credentials for, a consumer's own database — the security boundary this whole project exists to keep clean stays intact.
- **Negative:** a new module means a new thing to operate (dispatcher process/scheduled job, retry bookkeeping, delivery log storage/retention policy) — accepted as the necessary cost of the feature; the alternative (consumers polling a "recent events" endpoint) pushes the same complexity onto every consumer instead of centralizing it once.
- **Negative:** webhook delivery is inherently eventually-consistent and best-effort at the transport level (a consumer's endpoint can be down for the entire retry window) — consumers integrating something correctness-critical off a webhook alone need to also reconcile via the management API; this must be called out explicitly in integration docs, not left implicit.
- **Negative:** signing secret rotation for an existing endpoint (compromise, routine rotation) is a real operational feature this ADR does not fully specify yet — flagged as an open question below.

## Alternatives considered

- **Clavaris writes directly into the consumer's database** — rejected outright: breaks the module/security boundary, requires Clavaris to hold credentials for arbitrary third-party databases, and makes "any language, any framework" impossible (a consumer's schema is its own business).
- **Consumers poll a `/events` API** — rejected for v1: pushes latency and infrastructure cost (a polling loop, cursor/offset bookkeeping) onto every consumer instead of centralizing it once; kept as a possible v2 complement for consumers who want a durable event log to replay from scratch, not a replacement for webhooks.
- **Push to a message broker the consumer subscribes to (Kafka, RabbitMQ, SQS)** — rejected for v1: forces every consumer to run or pay for broker infrastructure just to integrate, directly contradicting the "under a day, no custom SDK" goal (`vision-document.md` §2). Worth revisiting only if a future consumer specifically needs broker-grade guarantees webhooks can't offer.

## Open questions

- Signing secret rotation flow (dual-secret overlap window, similar in spirit to `SigningKey` rotation in `identity-module`) — not designed yet, needed before this ADR can move to ✅ Aprobado.
- Delivery log retention window (how long `WEBHOOK_DELIVERIES` rows are kept before archival/deletion) — deferred, same "revisit once real usage data exists" stance as `data-model.md` §4.
- Whether `webhook-module` needs its own outbox-cleanup job (marking very old published rows for deletion) to bound `event_outbox` growth — flagged, not blocking.

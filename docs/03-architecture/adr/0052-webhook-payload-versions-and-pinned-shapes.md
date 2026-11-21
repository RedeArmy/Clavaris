# ADR-0052: A webhook delivery says which version of the payload it carries, and a test pins every payload's shape

**Status:** 🟡 Pending review (2026-11-20)

Closes TD-ARCH-064 (the 14th review, `docs/04-security/security-audit-2026-11-20.md`).

## Context

A webhook body is `objectMapper.writeValueAsString(eventRecord)`: the JSON of the Java record the use case handed to the outbox (`JpaEventOutboxWriter`), stored as text and sent as it is (ADR-0007). The deliveries carried `Clavaris-Event-Type`, `-Event-Id`, `-Delivery-Id`, `-Signature` and `-Trace-Id`, and nothing said which shape the body had. So renaming a field of `AccountRegisteredEvent` while refactoring changed a published contract without anyone deciding it, and a consumer could neither tell which shape it had received nor be warned. No consumer had integrated a receiver yet, which is the cheapest moment: a flag day costs nothing until there is someone to break.

## Decision

1. **`Clavaris-Event-Version` on every delivery** (`WebhookEventContract.VERSION_HEADER`, value `WebhookEventContract.CURRENT_VERSION`, `1`). A header and not a body field: the stored body is the exact byte sequence that is signed and that a replay sends again, so adding a field on the way out would make a replay differ from the original it replays, and would change what the signature covers.
2. **The rule** (also in `docs/03-architecture/api-contracts/webhook-contract.md` for integrators): adding a field is not a new version, because receivers must ignore fields they do not know; removing, renaming, retyping or changing the meaning of a field is a new version, and the previous one is delivered for at least 90 days after the announcement.
3. **A test pins every shape.** `WebhookPayloadContractTest` finds every event record of the identity, organization and client-registry modules on the class path, builds one with fixed sample values, serialises it with the application's own Jackson configuration (Spring Boot's auto-configuration, not a hand-built mapper) and compares it with `webhook-contracts/v<version>/<Event>.json`. A new event with no sample fails, a changed shape fails and says whether it is an addition (update the sample) or a new version, and a sample for an event that no longer exists fails. The directory is per version, so a second version adds `v2/` beside `v1/`, which stays as the record of what was delivered.
4. **The test reads the wire, not the intent.** What the samples show is what any consumer written today would parse, including the way the identifier value types serialise. It is pinned as v1 as it is; changing it is a version.

## Not done, and why

- **The endpoint does not record the version it was registered against, and the outbox row does not carry it.** There is one version, so there is nothing to choose between, and a column added for a second version that does not exist yet is speculation. When the first change that needs a v2 arrives, the endpoint gets an `event_version` (default 1) and the dispatcher renders the shape it asks for; the contract test already keeps the v1 shapes. Registered as TD-FUT-088.
- **A body envelope** (`{"version":1,"type":"...","data":{...}}`) would have been a breaking change to the only shape that exists, for no gain over a header.
- **Tidying the identifier wrappers** before any consumer depends on them. It would be a free change now and a v2 later; it is a decision for whoever first writes a receiver (TD-FUT-080's test event is the natural moment) and is not made here.
- **Schemas** (JSON Schema, AsyncAPI) generated from the records. The samples are the contract for now; a schema is the next step when a consumer asks for one.

## Consequences

- A refactor that touches an event record fails a test on purpose, with the instruction for each of the three cases.
- An integrator has a page (`webhook-contract.md`) with the headers, the signature verification (with code) and the versioning rule.
- Replays and retries are unchanged: the stored bytes, the signature and the ids are exactly as before; only one header is added.

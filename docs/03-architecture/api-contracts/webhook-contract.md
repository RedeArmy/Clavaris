# Webhook contract (v1)

🟡 En revisión

What a receiver of a Clavaris webhook can rely on, and what it must not. The reasoning is in ADR-0007 (the delivery) and ADR-0052 (the versions); this page is what to give an integrator.

## 1. What is sent

One `POST` per event per registered endpoint, `Content-Type: application/json`, the body being the event's JSON exactly as stored. The samples of every event are in `app/src/test/resources/webhook-contracts/v1/` (one file per event record) and are checked on every build: a change to one cannot reach a consumer without a decision.

| Header | Meaning |
|---|---|
| `Clavaris-Event-Type` | The event, for example `account.created`. |
| `Clavaris-Event-Id` | Identifies the event. The same event sent to two endpoints, or again after a failure or a replay, carries the same id: **deduplicate on it**. |
| `Clavaris-Delivery-Id` | Identifies this delivery to this endpoint. |
| `Clavaris-Event-Version` | The version of the payload shape (`1` today). See section 3. |
| `Clavaris-Signature` | `t=<unix seconds>,v1=<hex>[,v1=<hex>]`. See section 2. |
| `Clavaris-Trace-Id` | Present when the event was caused by a traced request; quote it in a support request. |

Delivery is at least once. A response with a 2xx status is success; anything else, a timeout or a redirect (not followed) is retried with a growing delay for a bounded number of attempts, and a delivery that is out of attempts can be replayed by an operator. Receivers should answer quickly and process afterwards.

## 2. Verifying the signature

1. Take the `t` and every `v1` from `Clavaris-Signature`. More than one `v1` appears while a signing secret is being rotated: accept the delivery if **any** matches.
2. Reject it if `t` is more than five minutes from your clock (replay protection).
3. Compute `HMAC-SHA256(secret, t + "." + rawBody)` over the **raw bytes of the body as received**, not over a parsed and re-serialised value, and compare the lowercase hex with a constant-time comparison.

```python
import hashlib, hmac, time


def verify(secret: bytes, signature_header: str, raw_body: bytes, tolerance: int = 300) -> bool:
    timestamp = ""
    candidates = []
    for item in signature_header.split(","):
        key, _, value = item.partition("=")
        if key == "v1":
            candidates.append(value)
        elif key == "t":
            timestamp = value
    if not timestamp.isdigit() or abs(time.time() - int(timestamp)) > tolerance:
        return False
    expected = hmac.new(secret, timestamp.encode() + b"." + raw_body, hashlib.sha256).hexdigest()
    return any(hmac.compare_digest(expected, candidate) for candidate in candidates)
```

## 3. Versions and what may change without one

The payload is the serialisation of the event record, so its shape is a contract. The rules:

- **Receivers must ignore fields they do not know.** A field added to an event is not a new version.
- **A field removed, renamed, retyped or changed in meaning is a new version.** `Clavaris-Event-Version` is raised, and the previous version keeps being delivered for **at least 90 days** after the announcement in the release notes.
- **A new event type is not a version.** An endpoint receives only the event types it registered for.
- The version is a header and not a field of the body because the stored body is the exact byte sequence that is signed and that a replay sends again; changing it on the way out would make a replay differ from the original.

Not yet: there is one version, so there is nothing to pin. When a second version is introduced, an endpoint will record the version it was registered against and receive that one until it is moved (TD-FUT-088).

## 4. What a receiver should not assume

- Order. Events can arrive out of order, and a retry can overtake a later event; use `occurredAt` and the aggregate's own state, not arrival order.
- That an identifier is a bare string. An id is an opaque UUID value and its JSON wrapper is part of v1, pinned like the rest (ADR-0052 explains why it is left as it is).
- That every field is present. A field documented as optional in a sample is `null` or absent.

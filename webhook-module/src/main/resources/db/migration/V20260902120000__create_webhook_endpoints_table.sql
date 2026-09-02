-- ADR-0007 §1/§2: one registered "push events here" URL per row, scoped to exactly one
-- Organization (ADR-0010). subscribed_event_types is stored as text/JSON array — same convention
-- as oauth_clients.redirect_uris/allowed_grant_types/allowed_scopes (data-model.md §2).
--
-- current_secret_encrypted/previous_secret_encrypted hold already-encrypted material (AES-256-GCM,
-- WebhookSigningSecretCipher), never plaintext or a one-way hash — see WebhookEndpoint's own
-- Javadoc for why a one-way hash (oauth_clients.client_secret_hash's own convention) can't be the
-- storage shape here: this secret must be recoverable in cleartext to compute an outbound HMAC at
-- delivery time.
CREATE TABLE webhook_endpoints (
    id                          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id             uuid NOT NULL,
    url                         varchar(2048) NOT NULL,
    description                 varchar(500),
    subscribed_event_types      text NOT NULL,
    current_secret_encrypted    text NOT NULL,
    previous_secret_encrypted   text,
    previous_secret_expires_at  timestamptz,
    active                      boolean NOT NULL DEFAULT true,
    created_at                  timestamptz NOT NULL DEFAULT now()
);

-- The dispatcher's own fan-out lookup (DispatchOutboxEventsService): active endpoints for one
-- Organization, filtered further in-memory by subscribed_event_types (a JSON containment index
-- would help once real volume justifies it — not yet, same "revisit once real usage data exists"
-- stance data-model.md §4 already takes elsewhere).
CREATE INDEX ix_webhook_endpoints_organization_id_active
    ON webhook_endpoints (organization_id) WHERE active = true;

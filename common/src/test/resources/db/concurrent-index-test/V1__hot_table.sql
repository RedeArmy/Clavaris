CREATE TABLE hot_events (
    id bigserial PRIMARY KEY,
    organization_id uuid NOT NULL,
    occurred_at timestamptz NOT NULL DEFAULT now()
);
INSERT INTO hot_events (organization_id) SELECT gen_random_uuid() FROM generate_series(1, 1000);

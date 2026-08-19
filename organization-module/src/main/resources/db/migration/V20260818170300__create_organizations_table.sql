-- data-model.md §2, ADR-0010: the tenant isolation boundary — one row per consuming system.
-- No unique constraint on name: not documented as a business rule, and two distinct consumers
-- could legitimately share a display name (data-model.md doesn't list one either).
CREATE TABLE organizations (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name       varchar(255) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

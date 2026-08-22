-- Same rationale as password_credentials — a separate table, never a nullable column on
-- platform_accounts.
CREATE TABLE platform_password_credentials (
    id                    uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    platform_account_id   uuid NOT NULL REFERENCES platform_accounts (id),
    password_hash         varchar(255) NOT NULL,
    updated_at            timestamptz NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX ux_platform_password_credentials_platform_account_id
    ON platform_password_credentials (platform_account_id);

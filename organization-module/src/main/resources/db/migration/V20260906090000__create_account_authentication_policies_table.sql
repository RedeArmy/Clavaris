-- ADR-0024 (sign-up/sign-in options, Clerk parity): the per-Organization configuration surface for
-- which identifiers/strategies its own Account population may use to sign up and sign in. Real FK
-- + ON DELETE CASCADE from day one — organization-module owns both this table and organizations
-- itself, same precedent rate_limit_policies/organization_social_credentials already establish.
--
-- Absence of a row for a given Organization means "use the defaults baked into
-- AccountAuthenticationPolicy.defaults()," not "unconfigured" — same convention rate_limit_policies
-- already establishes for its own absence-of-row case.
CREATE TABLE account_authentication_policies (
    id                                       uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id                          uuid NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    email_verification_required_at_sign_in   boolean NOT NULL DEFAULT false,
    email_verification_method                varchar(10) NOT NULL DEFAULT 'LINK',
    email_code_sign_in_enabled               boolean NOT NULL DEFAULT false,
    email_link_sign_in_enabled               boolean NOT NULL DEFAULT false,
    username_sign_up_enabled                 boolean NOT NULL DEFAULT false,
    username_required                        boolean NOT NULL DEFAULT false,
    username_sign_in_enabled                 boolean NOT NULL DEFAULT false,
    password_at_sign_up_enabled              boolean NOT NULL DEFAULT true,
    device_trust_enabled                     boolean NOT NULL DEFAULT false,
    created_at                                timestamptz NOT NULL DEFAULT now(),
    updated_at                                timestamptz NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX ux_account_authentication_policies_organization_id
    ON account_authentication_policies (organization_id);

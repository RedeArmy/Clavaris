package com.clavaris.organization.application.usecase.getorganizationapikeys;

/**
 * Mirrors https://clerk.com/docs/guides/development/clerk-environment-variables — every field here
 * is either purely derived (no new storage) or a straight surfacing of infrastructure that already
 * exists (the Organization's own issuer/JWKS, ADR-0010 §5).
 *
 * @param publishableKey {@code pk_test_}/{@code pk_live_} + base64url(organizationId) — zero new
 *     storage, safe to share by construction: an opaque, non-secret routing identifier, same shape
 *     Clerk's own key literally is (it encodes routing info, it isn't a credential).
 * @param frontendApiUrl the Organization's own tenant issuer (ADR-0010 §5.1)
 * @param backendApiUrl the one shared {@code client_credentials} token endpoint (ADR-0023 §2) —
 *     reached with either a {@code PlatformClient} or an {@code OrganizationClient} (Secret Key)
 * @param jwksUrl the Organization's own JWKS document
 * @param jwksPublicKey the Organization's own active signing key, PEM-encoded
 * @param configuredApiVersion ADR-0008's own URI-path version this response itself was served under
 *     — {@code "v1"} today, the only version that has ever existed
 * @param latestApiVersion the newest version this deployment serves — identical to {@code
 *     configuredApiVersion} until a {@code v2} ships
 */
@SuppressWarnings("PMD.LongVariable") // configuredApiVersion/latestApiVersion name exactly what
// they are — mirrors Clerk's own field names, not arbitrarily long.
public record OrganizationApiKeys(
    String publishableKey,
    String frontendApiUrl,
    String backendApiUrl,
    String jwksUrl,
    String jwksPublicKey,
    String configuredApiVersion,
    String latestApiVersion) {}

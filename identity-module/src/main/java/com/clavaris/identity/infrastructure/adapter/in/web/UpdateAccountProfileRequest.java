package com.clavaris.identity.infrastructure.adapter.in.web;

/**
 * HTTP request body for {@code PUT /api/v1/admin/accounts/{id}/profile} (TD-FUT-041). Every field
 * is nullable, same "omitted means leave unconfigured" convention {@code SetClientBrandingRequest}
 * already establishes — {@code firstName}/{@code lastName} plain-overwrite when present (never
 * cleared to blank by omission), {@code username}/{@code phoneNumber} apply only when the Account
 * doesn't already have one (see {@code UpdateAccountProfileCommand}'s own Javadoc for why — the
 * exact same rule this request's own values feed into, unchanged for this second, Backend-API
 * caller).
 */
public record UpdateAccountProfileRequest(
    String firstName, String lastName, String username, String phoneNumber) {}

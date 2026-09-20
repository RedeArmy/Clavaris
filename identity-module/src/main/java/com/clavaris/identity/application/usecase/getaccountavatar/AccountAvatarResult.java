package com.clavaris.identity.application.usecase.getaccountavatar;

/**
 * {@link GetAccountAvatarUseCase}'s own two real shapes (ADR-0026): {@link Redirect} for a social
 * provider's own external picture URL (captured once at registration, never re-hosted — the browser
 * fetches it directly from that provider's own CDN), {@link Content} for everything Clavaris itself
 * serves the bytes of — a real Supabase-stored upload, or {@code GetAccountAvatarService}'s own
 * generated-initials default when no picture is set at all. The stable {@code
 * /o/{organizationId}/avatars/{accountId}} URL is identical either way; only what happens once a
 * request reaches it differs.
 */
public sealed interface AccountAvatarResult {

  record Redirect(String externalUrl) implements AccountAvatarResult {}

  record Content(byte[] content, String contentType) implements AccountAvatarResult {}
}

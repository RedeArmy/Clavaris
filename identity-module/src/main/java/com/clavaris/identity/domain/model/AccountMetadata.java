package com.clavaris.identity.domain.model;

/**
 * TD-FUT-034 (Clerk "Metadata" parity): three raw-JSON-text tiers Clavaris never interprets, the
 * same "opaque, consumer-defined" posture {@code OAuthClient.allowedScopes} and {@code
 * WorkspaceRole.permissions} hold. {@code null} means never set or cleared. Syntactic JSON validity
 * is the caller's job; the domain stays free of a JSON library.
 *
 * @param publicMetadata readable by anyone who can read the account, never secret
 * @param privateMetadata backend-readable only, never exposed through an account-holder surface
 * @param unsafeMetadata named "unsafe" because a future self-service write path would let the
 *     account holder set it, so no caller may trust it for an authorization decision
 */
public record AccountMetadata(
    String publicMetadata, String privateMetadata, String unsafeMetadata) {

  public static final AccountMetadata EMPTY = new AccountMetadata(null, null, null);
}

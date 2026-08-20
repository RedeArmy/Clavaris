package com.clavaris.identity.application.usecase.authenticatewithpassword;

/**
 * Outbound port — implemented by {@code
 * infrastructure/adapter/out/security/Argon2PasswordVerifier}. Deliberately a separate port from
 * {@code registeraccount.PasswordHasher} rather than a second method bolted onto it: this
 * codebase's established convention is narrow, single-purpose ports named for exactly what they do
 * (see also {@code OrganizationExistsChecker}, {@code SigningKeyProvisioner}) — "hash" and "verify"
 * are different operations even though ADR-0005's Argon2 encoder happens to expose both.
 */
@FunctionalInterface
public interface PasswordVerifier {

  boolean matches(String rawPassword, String passwordHash);
}

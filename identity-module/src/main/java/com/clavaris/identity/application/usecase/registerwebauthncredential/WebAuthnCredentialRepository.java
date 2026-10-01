package com.clavaris.identity.application.usecase.registerwebauthncredential;

import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.WebAuthnCredential;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Outbound port — implemented in {@code
 * infrastructure/adapter/out/persistence/JpaWebAuthnCredentialRepository}. Owned by this package
 * (the write side, TD-FUT-034) and reused unmodified by {@code authenticatewithwebauthn} (lookup +
 * signature-count bump), {@code listwebauthncredentialsforaccount} (read), and {@code
 * deletewebauthncredential} — same one-port-many-consumers precedent {@code LoginEventRepository}
 * already sets for {@code recordloginevent}/{@code getloginactivityforaccount}.
 */
public interface WebAuthnCredentialRepository {

  void insert(WebAuthnCredential credential);

  /**
   * The actual WebAuthn authentication-ceremony lookup key — see {@code credentialId}'s own
   * Javadoc.
   */
  Optional<WebAuthnCredential> findByCredentialId(byte[] credentialId);

  /**
   * Clerk "View Profile" passkeys parity — newest-first, same no-pagination rationale as {@code
   * KnownDeviceRepository#findAllByAccountId}.
   */
  List<WebAuthnCredential> findAllByAccountId(AccountId accountId);

  /** Bumps the counter/last-used timestamp after a successfully verified assertion. */
  void updateSignatureCount(UUID credentialRowId, long newSignatureCount, Instant lastUsedAt);

  /**
   * Ownership-scoped delete — {@code accountId} must match the row's own owner or nothing is
   * deleted. Reused unmodified by both the self-service controller ({@code accountId} = the current
   * session's own) and the admin dashboard ({@code accountId} = the resolved target Account) —
   * neither can delete a credential belonging to a different Account by passing the wrong id.
   *
   * @return {@code true} if a row was actually deleted, {@code false} for "not found or not yours."
   */
  boolean deleteByIdAndAccountId(UUID credentialRowId, AccountId accountId);
}

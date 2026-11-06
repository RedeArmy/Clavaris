package com.clavaris.identity.application.usecase.starttotpenrollment;

import com.clavaris.identity.domain.model.AccountId;

/**
 * Encrypts the authenticator secret at rest. It is a secret the server must read back, so unlike a
 * password it cannot be hashed. Each secret is bound to its Account (TD-SEC-086): one Account's
 * secret copied into another's row does not decrypt.
 */
public interface TotpSecretCipher {

  String encrypt(AccountId accountId, String plainSecret);

  String decrypt(AccountId accountId, String encryptedSecret);
}

package com.clavaris.identity.application.usecase.starttotpenrollment;

/**
 * Encrypts the authenticator secret at rest. It is a secret the server must read back, so unlike a
 * password it cannot be hashed.
 */
public interface TotpSecretCipher {

  String encrypt(String plainSecret);

  String decrypt(String encryptedSecret);
}

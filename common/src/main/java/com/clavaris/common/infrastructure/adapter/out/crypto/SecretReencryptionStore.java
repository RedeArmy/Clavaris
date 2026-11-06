package com.clavaris.common.infrastructure.adapter.out.crypto;

import com.clavaris.common.infrastructure.adapter.out.crypto.StoredSecretReencryptor.Target;
import java.util.List;

/**
 * TD-SEC-089: what the re-encryption sweep needs to know about one kind of at-rest secret, stated
 * by the cipher that owns it so the sweep itself is written once. The cipher already holds the
 * {@link EnvelopeCipher} and knows which table and columns its values live in and how their context
 * is built, so nothing about a store is repeated anywhere else.
 */
public interface SecretReencryptionStore {

  /** The store's name, as in the metrics tag and the lock: {@code totp-secret}, ... */
  String store();

  EnvelopeCipher envelope();

  /** Every column holding a value this cipher wrote. */
  List<Target> targets();
}

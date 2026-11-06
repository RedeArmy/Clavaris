package com.clavaris.common.infrastructure.adapter.out.crypto;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * TD-SEC-086: the format every at-rest secret is stored in, so a copied ciphertext does not decrypt
 * elsewhere and a key can be replaced without stopping the service.
 *
 * <p>Shape: {@code v1.<keyId>.<base64(iv || ciphertext+tag)>}. Two properties:
 *
 * <ul>
 *   <li><b>Bound to its row.</b> The text {@code purpose} and {@code context} (the table, column
 *       and row, by the caller) are authenticated as associated data and not stored. Someone with
 *       write access to the database who copies one Account's authenticator secret into another's
 *       row, or one Organization's social secret into another's, makes decryption fail instead of
 *       handing over a secret they chose.
 *   <li><b>Rotatable.</b> The key id names which key encrypted the value. Encryption always uses
 *       the active key; decryption accepts any key in the ring. To rotate: add the new key as
 *       active under a new id and keep the old one as a previous key; both work until every value
 *       has been rewritten, and only then is the old key removed.
 * </ul>
 *
 * <p>A value without the {@code v1.} prefix is the format written before this change: no associated
 * data, one key. It is still read, with the key whose id is {@link #LEGACY_KEY_ID}, which is what
 * the single configured key is called unless set otherwise; {@link #isLegacy} says which a stored
 * value is, so a caller can count them and know when the old path can be removed. Nothing is ever
 * written in it again.
 */
public final class EnvelopeCipher {

  /** The id the one key that existed before key ids is given. */
  public static final String LEGACY_KEY_ID = "1";

  private static final String PREFIX = "v1.";
  private static final String DOMAIN = "clavaris/envelope/v1";
  private static final Pattern KEY_ID = Pattern.compile("[A-Za-z0-9_-]{1,16}");

  private final String purpose;
  private final String activeKeyId;
  private final Map<String, AesGcmCipher> ring = new LinkedHashMap<>();
  private final Runnable legacyReadObserver;

  /**
   * @param purpose names the store ({@code totp-secret}, ...); part of the associated data, so a
   *     value from one store never decrypts in another even under an equal key
   * @param activeKeyId the id of the key that encrypts
   * @param activeBase64Key that key, 256 bits, base64
   * @param previousKeys {@code id:base64,id:base64}, keys that still decrypt; blank for none
   * @throws IllegalArgumentException for a malformed id or key, or a repeated id; the message never
   *     contains key material
   */
  public EnvelopeCipher(
      final String purpose,
      final String activeKeyId,
      final String activeBase64Key,
      final String previousKeys) {
    this(purpose, activeKeyId, activeBase64Key, previousKeys, null);
  }

  /**
   * @param legacyReadObserver run each time a value in the format written before this one is read,
   *     so a caller can count them and know when none are left; {@code null} for none
   */
  public EnvelopeCipher(
      final String purpose,
      final String activeKeyId,
      final String activeBase64Key,
      final String previousKeys,
      final Runnable legacyReadObserver) {
    this.legacyReadObserver = legacyReadObserver == null ? () -> {} : legacyReadObserver;
    this.purpose = requireText(purpose, "purpose");
    this.activeKeyId = requireKeyId(activeKeyId);
    ring.put(this.activeKeyId, new AesGcmCipher(activeBase64Key));
    if (previousKeys != null && !previousKeys.isBlank()) {
      for (final String entry : previousKeys.split(",")) {
        final int colon = entry.indexOf(':');
        if (colon < 0) {
          throw new IllegalArgumentException("A previous key must be written id:base64");
        }
        final String id = requireKeyId(entry.substring(0, colon).strip());
        // putIfAbsent answers with what was already there, so a repeated id is caught by the one
        // call
        // that would otherwise have replaced the key.
        if (ring.putIfAbsent(id, new AesGcmCipher(entry.substring(colon + 1).strip())) != null) {
          throw new IllegalArgumentException("The key id '" + id + "' is used more than once");
        }
      }
    }
  }

  /** Encrypts under the active key, bound to {@code context}. */
  public String encrypt(final String plaintext, final String context) {
    final String body = ring.get(activeKeyId).encrypt(plaintext, aad(context));
    return PREFIX + activeKeyId + "." + body;
  }

  /**
   * @throws IllegalStateException if the value was tampered with, belongs to another context or
   *     purpose, or was written under a key that is not in the ring
   */
  public String decrypt(final String stored, final String context) {
    if (isLegacy(stored)) {
      legacyReadObserver.run();
      return cipherFor(LEGACY_KEY_ID).decrypt(stored, null);
    }
    final int keyEnd = stored.indexOf('.', PREFIX.length());
    if (keyEnd < 0) {
      throw new IllegalStateException("Not a valid encrypted value");
    }
    final String keyId = stored.substring(PREFIX.length(), keyEnd);
    return cipherFor(keyId).decrypt(stored.substring(keyEnd + 1), aad(context));
  }

  /** True for a value written before this format, which is read but never written again. */
  public boolean isLegacy(final String stored) {
    return !stored.startsWith(PREFIX);
  }

  private AesGcmCipher cipherFor(final String keyId) {
    final AesGcmCipher found = ring.get(keyId);
    if (found == null) {
      throw new IllegalStateException(
          "The value was encrypted under key '"
              + keyId
              + "', which is not configured; add it as a previous key");
    }
    return found;
  }

  private byte[] aad(final String context) {
    return (DOMAIN + "|" + purpose + "|" + requireText(context, "context"))
        .getBytes(StandardCharsets.UTF_8);
  }

  private static String requireText(final String value, final String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("The " + name + " must not be blank");
    }
    return value;
  }

  private static String requireKeyId(final String id) {
    if (id == null || !KEY_ID.matcher(id).matches()) {
      throw new IllegalArgumentException(
          "A key id is 1 to 16 letters, digits, '-' or '_', and has no '.' or ':'");
    }
    return id;
  }
}

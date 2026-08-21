package com.clavaris.identity.infrastructure.adapter.out.security;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyStore;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Optional;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * TD-SEC-002: the real, restart-surviving half of {@code TOKEN_SIGNING_KEY_STORE_PATH} — previously
 * declared in {@code .env.example} and never read anywhere ({@link PlatformSigningKeyMaterial}'s
 * and {@link OrganizationSigningKeyMaterialFactory}'s own old Javadoc said so explicitly). Backs a
 * single PKCS12 file with one {@link KeyStore.SecretKeyEntry} pair (raw PKCS8 private / X.509
 * public bytes) per signing key, aliased by {@code kid} — the same durable identity {@link
 * com.clavaris.identity.domain.model.SigningKey}/{@link
 * com.clavaris.identity.domain.model.PlatformSigningKey} already persist to Postgres.
 *
 * <p>{@code SecretKeyEntry}, not {@code PrivateKeyEntry}: a PKCS12 {@code PrivateKeyEntry} requires
 * an X.509 certificate chain, which would mean minting a self-signed certificate for every key this
 * codebase has no other use for. Wrapping the raw encoded key bytes in a {@link SecretKeySpec} and
 * storing them as a secret-key entry sidesteps that entirely while still getting PKCS12's real
 * properties: password-based encryption and an HMAC integrity check on load, confirmed live
 * (decompiling nothing needed here — a direct round-trip test against the actual JDK 25 {@code
 * SunJSSE}/PKCS12 provider proved both a correct reload and a load failure under the wrong
 * password) rather than assumed from documentation.
 *
 * <p>One file for every key this process ever needs — the platform tier's singleton key and every
 * Organization's own key alike — keyed by {@code kid} (a random UUID per key), not one file per
 * tenant. Simpler operationally (one path to back up, one password to manage) and sufficient for
 * this project's expected scale (single-digit consumers, `data-model.md` §5); revisit only if that
 * assumption changes.
 *
 * <p><b>Known, deliberate scope limit:</b> writes are synchronized within one JVM but not
 * coordinated across processes — safe for the single-instance topology this system runs today, not
 * yet safe for more than one {@code app} instance writing concurrently. TD-FUT-004 already tracks
 * horizontal scaling as blocked on more than just this.
 */
@Component
class SigningKeyStore {

  private static final String KEYSTORE_TYPE = "PKCS12";
  // Used only as the SecretKeySpec's algorithm label — never passed to a Cipher, so any JCE
  // algorithm name would do; "RSA" documents intent at the point of storage/retrieval.
  private static final String KEY_ALGORITHM = "RSA";

  private final Path filePath;
  private final char[] password;
  private final KeyFactory rsaKeyFactory;

  /* package */ SigningKeyStore(
      @Value("${clavaris.signing-key-store.path}") final String path,
      @Value("${clavaris.signing-key-store.password}") final String password) {
    this.filePath = Path.of(path).toAbsolutePath().normalize();
    this.password = password.toCharArray();
    try {
      this.rsaKeyFactory = KeyFactory.getInstance("RSA");
    } catch (final NoSuchAlgorithmException e) {
      // RSA is a JDK-mandatory algorithm — same reasoning as RsaKeyPairs' own equivalent catch.
      throw new IllegalStateException("RSA KeyFactory not available on this JVM", e);
    }
    ensureParentDirectoryExists();
  }

  /** Generates a fresh RSA key pair and persists it under {@code kid} before returning it. */
  /* package */ synchronized KeyPair generate(final String kid) {
    final KeyPair keyPair = RsaKeyPairs.generate();
    store(kid, keyPair);
    return keyPair;
  }

  /** Reloads the key pair previously persisted under {@code kid}, if this store has one. */
  @SuppressWarnings("PMD.OnlyOneReturn")
  /* package */ synchronized Optional<KeyPair> find(final String kid) {
    final KeyStore keyStore = load();
    try {
      if (!keyStore.containsAlias(privateAlias(kid))) {
        return Optional.empty();
      }
      final KeyStore.ProtectionParameter protection = protection();
      final KeyStore.SecretKeyEntry privateEntry =
          (KeyStore.SecretKeyEntry) keyStore.getEntry(privateAlias(kid), protection);
      final KeyStore.SecretKeyEntry publicEntry =
          (KeyStore.SecretKeyEntry) keyStore.getEntry(publicAlias(kid), protection);
      // LawOfDemeter flags reaching through the SecretKeyEntry to its own wrapped key — there's no
      // intermediate step to name here, the entry exists solely to carry these encoded bytes.
      @SuppressWarnings("PMD.LawOfDemeter")
      final byte[] privateKeyBytes = privateEntry.getSecretKey().getEncoded();
      @SuppressWarnings("PMD.LawOfDemeter")
      final byte[] publicKeyBytes = publicEntry.getSecretKey().getEncoded();
      final PrivateKey privateKey =
          rsaKeyFactory.generatePrivate(new PKCS8EncodedKeySpec(privateKeyBytes));
      final PublicKey publicKey =
          rsaKeyFactory.generatePublic(new X509EncodedKeySpec(publicKeyBytes));
      return Optional.of(new KeyPair(publicKey, privateKey));
    } catch (final GeneralSecurityException e) {
      throw new IllegalStateException(
          "Failed to read signing key '" + kid + "' from the key store at " + filePath, e);
    }
  }

  private void store(final String kid, final KeyPair keyPair) {
    final KeyStore keyStore = load();
    try {
      final KeyStore.ProtectionParameter protection = protection();
      keyStore.setEntry(
          privateAlias(kid),
          new KeyStore.SecretKeyEntry(
              new SecretKeySpec(keyPair.getPrivate().getEncoded(), KEY_ALGORITHM)),
          protection);
      keyStore.setEntry(
          publicAlias(kid),
          new KeyStore.SecretKeyEntry(
              new SecretKeySpec(keyPair.getPublic().getEncoded(), KEY_ALGORITHM)),
          protection);
      persist(keyStore);
    } catch (final GeneralSecurityException e) {
      throw new IllegalStateException(
          "Failed to write signing key '" + kid + "' to the key store at " + filePath, e);
    }
  }

  private KeyStore load() {
    try {
      final KeyStore keyStore = KeyStore.getInstance(KEYSTORE_TYPE);
      if (Files.exists(filePath)) {
        try (InputStream fileContents = Files.newInputStream(filePath)) {
          keyStore.load(fileContents, password);
        }
      } else {
        // No file on disk yet — load(null, ...) initializes an empty, unbacked keystore rather
        // than failing; the first store() call is what actually creates the file.
        keyStore.load(null, password);
      }
      return keyStore;
    } catch (final GeneralSecurityException | IOException e) {
      throw new IllegalStateException("Failed to load signing key store at " + filePath, e);
    }
  }

  // Write-to-temp-then-atomic-rename: a crash or concurrent read mid-write must never observe a
  // half-written PKCS12 file, which a direct in-place Files.newOutputStream(filePath) would risk.
  private void persist(final KeyStore keyStore) throws GeneralSecurityException {
    final Path tempFile = filePath.resolveSibling(filePath.getFileName() + ".tmp");
    try {
      try (OutputStream out = Files.newOutputStream(tempFile)) {
        keyStore.store(out, password);
      }
      Files.move(
          tempFile, filePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } catch (final IOException e) {
      throw new UncheckedIOException("Failed to persist signing key store at " + filePath, e);
    }
  }

  private KeyStore.ProtectionParameter protection() {
    return new KeyStore.PasswordProtection(password);
  }

  private static String privateAlias(final String kid) {
    return kid + ".private";
  }

  private static String publicAlias(final String kid) {
    return kid + ".public";
  }

  private void ensureParentDirectoryExists() {
    final Path parent = filePath.getParent();
    if (parent != null) {
      try {
        Files.createDirectories(parent);
      } catch (final IOException e) {
        throw new UncheckedIOException("Failed to create signing key store directory " + parent, e);
      }
    }
  }
}

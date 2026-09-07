package com.clavaris.identity.infrastructure.adapter.out.security;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.InvalidKeyException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyStore;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * TD-SEC-002: the real, restart-surviving half of {@code TOKEN_SIGNING_KEY_STORE_PATH} — previously
 * declared in {@code .env.example} and never read anywhere ({@link PlatformSigningKeyMaterial}'s
 * and {@link OrganizationSigningKeyMaterialFactory}'s own old Javadoc said so explicitly). Backs a
 * PKCS12 file with one {@link KeyStore.SecretKeyEntry} pair (raw PKCS8 private / X.509 public
 * bytes) per signing key, aliased by {@code kid} — the same durable identity {@link
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
 * <p><b>TD-SEC-054 (real per-tenant isolation at rest, not just per-tenant key material):</b> this
 * class used to back the platform tier's singleton key <em>and</em> every Organization's own key
 * with one shared file protected by one shared password ({@code TOKEN_SIGNING_KEY_STORE_PASSWORD})
 * — cryptographically distinct keys per {@link KeyStoreScope#organization}, but a single point of
 * failure for confidentiality at rest: anyone who obtained that one file plus that one password got
 * every tenant's private key material at once, not just one. {@code security-architecture.md}'s own
 * "a signing-key compromise is a single-tenant incident" claim was accurate for an
 * <em>application-layer</em> compromise (a bug signing with the wrong Organization's key, a JWKS
 * response leaking another tenant's public key) but overstated for a <em>storage-layer</em> one,
 * since the KeyStore API itself always requires exactly one password to open a PKCS12 container at
 * all — a per-entry password distinct from the container's own would still leave the container's
 * structure (and therefore every alias it holds) reachable to anyone who has that one shared
 * open-password, so per-entry passwords inside one shared file could never have actually solved
 * this; only separate files can.
 *
 * <p>Now one file per {@link KeyStoreScope} (the platform tier's own file, and one per
 * Organization), each protected by its own password — <b>derived</b>, not separately generated and
 * stored: {@code HMAC-SHA256(masterSecret, "clavaris:signing-key-store:v1:" + scope.id())},
 * Base64url-encoded. {@code TOKEN_SIGNING_KEY_STORE_PASSWORD} becomes that master secret rather
 * than a password used directly — zero new secrets to provision or rotate per Organization, and a
 * leaked single file (a stray backup, a support engineer's debug copy) now exposes only that one
 * scope's key material. <b>Honest residual limit, not silently implied away:</b> if the master
 * secret itself leaks (not just one derived file), every scope's password is trivially re-derivable
 * from it — this closes the "one file leaks in isolation" case, not the "the root secret leaks"
 * case, which would need envelope encryption via an external KMS to close for real (evaluated and
 * deliberately deferred — disproportionate infrastructure for this project's current single-VM,
 * solo-operator stage, the same judgment ADR-0019 already made rejecting a comparable
 * externally-dependent secrets-management stack).
 *
 * <p>No migration path exists from the old single-shared-file layout, deliberately: no consumer has
 * ever sent real user traffic to this system (CLAUDE.md §6's own external-security-review gate,
 * still unscheduled), so there is no already-issued token or already-provisioned Organization whose
 * key material this change could orphan. If this lands after that gate is crossed, add a read-time
 * fallback to the legacy shared file before deploying — do not assume this note still applies then.
 *
 * <p><b>Known, deliberate scope limit (unchanged):</b> writes are synchronized within one JVM but
 * not coordinated across processes — safe for the single-instance topology this system runs today,
 * not yet safe for more than one {@code app} instance writing concurrently. TD-FUT-004 already
 * tracks horizontal scaling as blocked on more than just this.
 */
// TD-SEC-054: TooManyMethods — fileFor/derivePassword are small, genuinely distinct helpers the
// per-scope redesign needed (resolving a path, deriving a password), not accidental sprawl; the
// class still does exactly one thing (persist/retrieve signing keys), same "more small methods,
// not more responsibility" reasoning this codebase already applies elsewhere (e.g. OAuthClient's
// own identical suppression). LongVariable: DERIVATION_MAC_ALGORITHM/
// DERIVATION_CONTEXT_PREFIX name exactly what they hold, same precedent as every other
// long-but-precise constant name in this codebase.
@SuppressWarnings({"PMD.TooManyMethods", "PMD.LongVariable"})
@Component
class SigningKeyStore {

  private static final String KEYSTORE_TYPE = "PKCS12";
  // Used only as the SecretKeySpec's algorithm label — never passed to a Cipher, so any JCE
  // algorithm name would do; "RSA" documents intent at the point of storage/retrieval.
  private static final String KEY_ALGORITHM = "RSA";
  private static final String DERIVATION_MAC_ALGORITHM = "HmacSHA256";
  // Versioned so a future change to the derivation itself (a different algorithm, a different
  // context string shape) can coexist with already-derived passwords during a transition, the same
  // "don't silently reinterpret an already-persisted secret" discipline this class's own file
  // format already follows.
  private static final String DERIVATION_CONTEXT_PREFIX = "clavaris:signing-key-store:v1:";

  private final Path baseDir;
  private final byte[] masterSecret;
  private final KeyFactory rsaKeyFactory;

  /* package */ SigningKeyStore(
      @Value("${clavaris.signing-key-store.path}") final String path,
      @Value("${clavaris.signing-key-store.password}") final String masterSecret) {
    // TD-SEC-054: `path` named one file before this change; now it names the directory every
    // scope's own file lives in — the parent of whatever filename was configured, so an existing
    // TOKEN_SIGNING_KEY_STORE_PATH value (e.g. "/app/data/signing-keys.p12") keeps working
    // unchanged, it just now identifies a directory rather than the one file it used to.
    final Path configuredPath = Path.of(path).toAbsolutePath().normalize();
    this.baseDir = configuredPath.getParent() != null ? configuredPath.getParent() : configuredPath;
    this.masterSecret = masterSecret.getBytes(StandardCharsets.UTF_8);
    try {
      this.rsaKeyFactory = KeyFactory.getInstance("RSA");
    } catch (final NoSuchAlgorithmException e) {
      // RSA is a JDK-mandatory algorithm — same reasoning as RsaKeyPairs' own equivalent catch.
      throw new IllegalStateException("RSA KeyFactory not available on this JVM", e);
    }
    ensureBaseDirectoryExists();
  }

  /** Generates a fresh RSA key pair and persists it under {@code kid}, scoped to {@code scope}. */
  /* package */ synchronized KeyPair generate(final KeyStoreScope scope, final String kid) {
    final KeyPair keyPair = RsaKeyPairs.generate();
    store(scope, kid, keyPair);
    return keyPair;
  }

  /**
   * TD-SEC-052: permanently removes {@code kid}'s own entries from {@code scope}'s own key store,
   * if present — a no-op if that store never had one, since a real hard-delete cascade may
   * legitimately try to purge a {@code kid} that was already retired/purged some other way. Same
   * write-to-temp-then-atomic-rename durability as {@link #store}: a crash mid-write must never
   * leave a half-deleted PKCS12 file behind.
   */
  /* package */ synchronized void delete(final KeyStoreScope scope, final String kid) {
    final Path filePath = fileFor(scope);
    final KeyStore keyStore = load(scope, filePath);
    try {
      if (keyStore.containsAlias(privateAlias(kid))) {
        keyStore.deleteEntry(privateAlias(kid));
      }
      if (keyStore.containsAlias(publicAlias(kid))) {
        keyStore.deleteEntry(publicAlias(kid));
      }
      persist(scope, filePath, keyStore);
    } catch (final GeneralSecurityException e) {
      throw new IllegalStateException(
          "Failed to delete signing key '" + kid + "' from the key store at " + filePath, e);
    }
  }

  /** Reloads the key pair previously persisted under {@code kid} in {@code scope}, if any. */
  @SuppressWarnings("PMD.OnlyOneReturn")
  /* package */ synchronized Optional<KeyPair> find(final KeyStoreScope scope, final String kid) {
    final Path filePath = fileFor(scope);
    final KeyStore keyStore = load(scope, filePath);
    try {
      if (!keyStore.containsAlias(privateAlias(kid))) {
        return Optional.empty();
      }
      final KeyStore.ProtectionParameter protection = protection(scope);
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

  private void store(final KeyStoreScope scope, final String kid, final KeyPair keyPair) {
    final Path filePath = fileFor(scope);
    final KeyStore keyStore = load(scope, filePath);
    try {
      final KeyStore.ProtectionParameter protection = protection(scope);
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
      persist(scope, filePath, keyStore);
    } catch (final GeneralSecurityException e) {
      throw new IllegalStateException(
          "Failed to write signing key '" + kid + "' to the key store at " + filePath, e);
    }
  }

  private KeyStore load(final KeyStoreScope scope, final Path filePath) {
    try {
      final KeyStore keyStore = KeyStore.getInstance(KEYSTORE_TYPE);
      final char[] password = derivePassword(scope);
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
  private void persist(final KeyStoreScope scope, final Path filePath, final KeyStore keyStore)
      throws GeneralSecurityException {
    final Path tempFile = filePath.resolveSibling(filePath.getFileName() + ".tmp");
    try {
      try (OutputStream out = Files.newOutputStream(tempFile)) {
        keyStore.store(out, derivePassword(scope));
      }
      Files.move(
          tempFile, filePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } catch (final IOException e) {
      throw new UncheckedIOException("Failed to persist signing key store at " + filePath, e);
    }
  }

  private KeyStore.ProtectionParameter protection(final KeyStoreScope scope) {
    return new KeyStore.PasswordProtection(derivePassword(scope));
  }

  /** {@code {baseDir}/signing-keys-{scope}.p12} — see this class's own Javadoc, TD-SEC-054. */
  private Path fileFor(final KeyStoreScope scope) {
    return baseDir.resolve("signing-keys-" + scope.id() + ".p12");
  }

  // TD-SEC-054: HMAC-SHA256(masterSecret, context) rather than a stored-per-scope secret — the
  // master secret is the only thing that needs provisioning/rotating operationally, exactly the
  // "one password to manage" simplicity this class's own historical design already valued, while
  // still giving every scope a genuinely distinct derived password. Recomputed on every call rather
  // than cached: HMAC-SHA256 over a short input is microseconds, not worth the complexity of a
  // cache with its own invalidation story for a value this cheap to regenerate.
  private char[] derivePassword(final KeyStoreScope scope) {
    try {
      final Mac mac = Mac.getInstance(DERIVATION_MAC_ALGORITHM);
      mac.init(new SecretKeySpec(masterSecret, DERIVATION_MAC_ALGORITHM));
      final byte[] derived =
          mac.doFinal((DERIVATION_CONTEXT_PREFIX + scope.id()).getBytes(StandardCharsets.UTF_8));
      return Base64.getUrlEncoder().withoutPadding().encodeToString(derived).toCharArray();
    } catch (final NoSuchAlgorithmException | InvalidKeyException e) {
      // HmacSHA256 is a JDK-mandatory algorithm and masterSecret is never empty (bound-checked at
      // the config layer, same as every other required secret in this codebase) — same
      // "JDK-guaranteed algorithm, real failure would mean a broken JVM" reasoning as the RSA
      // KeyFactory catch in the constructor above.
      throw new IllegalStateException("Failed to derive signing key store password", e);
    }
  }

  private static String privateAlias(final String kid) {
    return kid + ".private";
  }

  private static String publicAlias(final String kid) {
    return kid + ".public";
  }

  private void ensureBaseDirectoryExists() {
    try {
      Files.createDirectories(baseDir);
    } catch (final IOException e) {
      throw new UncheckedIOException("Failed to create signing key store directory " + baseDir, e);
    }
  }
}

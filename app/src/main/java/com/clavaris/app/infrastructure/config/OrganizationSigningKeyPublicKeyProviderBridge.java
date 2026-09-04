package com.clavaris.app.infrastructure.config;

import com.clavaris.identity.domain.model.OrganizationId;
import com.clavaris.identity.infrastructure.adapter.out.security.OrganizationSigningKeyMaterialFactory;
import com.clavaris.organization.application.usecase.getorganizationapikeys.OrganizationSigningKeyPublicKeyProvider;
import java.security.PublicKey;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Implements organization-module's outbound port — the bridge lives in {@code app}, not either
 * business module, for the same module-graph reason {@code CreateOrganizationSigningKeyBridge}
 * does. Reads identity-module's already-existing {@code
 * OrganizationSigningKeyMaterialFactory#keyPairFor} (the same source {@code
 * OrganizationScopedJwkSource}/the real JWKS endpoint itself reads) — no new key-material
 * machinery, only a PEM-encoded surfacing of the public half of what already exists.
 */
@SuppressWarnings("PMD.LongVariable")
@Component
class OrganizationSigningKeyPublicKeyProviderBridge
    implements OrganizationSigningKeyPublicKeyProvider {

  private static final String PEM_HEADER = "-----BEGIN PUBLIC KEY-----";
  private static final String PEM_FOOTER = "-----END PUBLIC KEY-----";
  private static final int PEM_LINE_LENGTH = 64;

  private final OrganizationSigningKeyMaterialFactory keyMaterialFactory;

  /* package */ OrganizationSigningKeyPublicKeyProviderBridge(
      final OrganizationSigningKeyMaterialFactory keyMaterialFactory) {
    this.keyMaterialFactory = keyMaterialFactory;
  }

  @Override
  public Optional<String> pemPublicKeyFor(final UUID organizationId) {
    return keyMaterialFactory
        .keyPairFor(new OrganizationId(organizationId))
        .map(keyPair -> toPem(keyPair.getPublic()));
  }

  private static String toPem(final PublicKey publicKey) {
    final String base64 = Base64.getEncoder().encodeToString(publicKey.getEncoded());
    final StringBuilder pem = new StringBuilder(PEM_HEADER).append('\n');
    for (int index = 0; index < base64.length(); index += PEM_LINE_LENGTH) {
      pem.append(base64, index, Math.min(index + PEM_LINE_LENGTH, base64.length())).append('\n');
    }
    return pem.append(PEM_FOOTER).toString();
  }
}

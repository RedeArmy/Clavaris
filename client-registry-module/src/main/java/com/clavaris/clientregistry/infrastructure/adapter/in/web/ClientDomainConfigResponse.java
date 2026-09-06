package com.clavaris.clientregistry.infrastructure.adapter.in.web;

import com.clavaris.clientregistry.domain.model.ClientDomainConfig;
import java.time.Instant;
import java.util.UUID;

/**
 * Shared response shape for the request/verify/get endpoints on {@code .../domain-config} — all
 * three return the same {@link ClientDomainConfig}, just at different points in its lifecycle.
 * {@code dnsTxtChallengeToken} is deliberately included: the operator needs its exact value to
 * publish the TXT record in the first place, and it is never a secret (knowing it grants no
 * capability beyond what publishing a DNS record for a domain you already control already
 * requires).
 */
// PMD.LongVariable: verificationStatus/dnsTxtChallengeToken name exactly what ADR-0009 §2 itself
// calls the field, same SetClientBrandingResponse precedent.
@SuppressWarnings("PMD.LongVariable")
public record ClientDomainConfigResponse(
    UUID oauthClientId,
    String mode,
    String hostname,
    String verificationStatus,
    String dnsTxtChallengeToken,
    String embeddingOrigin,
    Instant verifiedAt,
    Instant updatedAt) {

  public static ClientDomainConfigResponse from(final ClientDomainConfig config) {
    return new ClientDomainConfigResponse(
        config.oauthClientId(),
        config.mode().map(Enum::name).orElse(null),
        config.hostname().orElse(null),
        config.verificationStatus().map(Enum::name).orElse(null),
        config.dnsTxtChallengeToken().orElse(null),
        config.embeddingOrigin().orElse(null),
        config.verifiedAt().orElse(null),
        config.updatedAt());
  }
}

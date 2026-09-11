package com.clavaris.identity.application.usecase.listsigningkeysfororganization;

import com.clavaris.identity.domain.model.OrganizationId;
import com.clavaris.identity.domain.model.SigningKey;
import java.util.List;

/**
 * Inbound port for the dashboard's own signing-key listing page (ADR-0025) — did not exist until
 * now, same "the concrete capability gap, not just the controller" pattern {@code
 * ListOAuthClientsUseCase}'s own Javadoc already documents for an identical situation. Returns
 * exactly what {@code app}'s own {@code OrganizationJwksPublishingSource} would currently publish
 * for this Organization (the active key, plus any retired key still inside the configured overlap
 * window) — the same set an operator/tenant owner needs to see to understand "what is JWKS
 * currently serving," not an unbounded full history.
 */
@FunctionalInterface
public interface ListSigningKeysForOrganizationUseCase {

  List<SigningKey> handle(OrganizationId organizationId);
}

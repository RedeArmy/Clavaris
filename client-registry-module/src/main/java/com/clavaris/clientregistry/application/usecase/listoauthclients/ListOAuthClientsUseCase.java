package com.clavaris.clientregistry.application.usecase.listoauthclients;

import com.clavaris.clientregistry.domain.model.OAuthClient;
import java.util.List;
import java.util.UUID;

/**
 * Inbound port for the dashboard's own real {@code OAuthClient} listing page — see {@code
 * technical-debt-register.md} TD-FUT-032 for why this use case, and the {@code
 * findAllByOrganizationId} method it depends on, didn't exist until now.
 */
@FunctionalInterface
public interface ListOAuthClientsUseCase {

  List<OAuthClient> handle(UUID organizationId);
}

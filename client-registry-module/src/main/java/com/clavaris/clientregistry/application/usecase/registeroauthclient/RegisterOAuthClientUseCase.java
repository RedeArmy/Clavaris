package com.clavaris.clientregistry.application.usecase.registeroauthclient;

/**
 * Inbound port for {@code POST /api/v1/admin/organizations/{organizationId}/clients} (ADR-0010,
 * api-contract-overview.md §3, operator-only).
 */
@FunctionalInterface
public interface RegisterOAuthClientUseCase {

  RegisterOAuthClientResult handle(RegisterOAuthClientCommand command);
}

package com.clavaris.clientregistry.application.usecase.listoauthclientspaged;

import com.clavaris.clientregistry.domain.model.OAuthClient;
import com.clavaris.common.domain.model.Page;

/**
 * TD-PERF-020: the dashboard's own paginated sibling of {@code ListOAuthClientsUseCase} — that use
 * case stays unbounded and remains the one {@code PlatformOAuthClientController} uses for its own
 * anti-enumeration ownership check (deactivate/rotate-secret must recognize a clientId regardless
 * of which page the dashboard happens to be showing); this one exists only for the list page's own
 * display.
 */
@FunctionalInterface
public interface ListOAuthClientsPagedUseCase {

  Page<OAuthClient> handle(ListOAuthClientsPagedQuery query);
}

package com.clavaris.clientregistry.application.usecase.getclientdomainconfig;

import com.clavaris.clientregistry.domain.model.ClientDomainConfig;
import java.util.UUID;

/**
 * Read side of the domain-config surface — never empty, same convention every GET-side use case
 * here follows.
 */
@FunctionalInterface
public interface GetClientDomainConfigUseCase {

  ClientDomainConfig handle(UUID oauthClientId);
}

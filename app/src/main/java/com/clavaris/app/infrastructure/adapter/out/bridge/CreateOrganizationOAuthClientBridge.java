package com.clavaris.app.infrastructure.adapter.out.bridge;

import com.clavaris.clientregistry.application.usecase.registeroauthclient.OAuthClientDefaults;
import com.clavaris.clientregistry.application.usecase.registeroauthclient.RegisterOAuthClientCommand;
import com.clavaris.clientregistry.application.usecase.registeroauthclient.RegisterOAuthClientResult;
import com.clavaris.clientregistry.application.usecase.registeroauthclient.RegisterOAuthClientUseCase;
import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.organization.application.usecase.createorganization.OAuthClientProvisioner;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Adapts organization-module's {@link OAuthClientProvisioner} outbound port to
 * client-registry-module's real {@link RegisterOAuthClientUseCase} — the bridge lives in {@code
 * app}, not either business module, same "needs both at once" reasoning as {@link
 * CreateOrganizationSigningKeyBridge}. Registers with {@link OAuthClientDefaults}' fixed grant
 * types/scopes/consent setting and no redirect URI configured yet — the raw client secret {@link
 * RegisterOAuthClientResult} carries is intentionally discarded here rather than echoed back
 * through this port: {@code CreateOrganizationResult} already isn't the place a one-time secret
 * gets surfaced (the operator/owner views and copies it from the OAuth Clients page like any other
 * client's secret, not from the organization-creation response).
 */
@Component
class CreateOrganizationOAuthClientBridge implements OAuthClientProvisioner {

  private final RegisterOAuthClientUseCase registerClient;

  /* package */ CreateOrganizationOAuthClientBridge(
      final RegisterOAuthClientUseCase registerClient) {
    this.registerClient = registerClient;
  }

  @Override
  public ProvisionedOAuthClient provisionFor(final UUID organizationId, final AuditActor actor) {
    final RegisterOAuthClientResult result =
        registerClient.handle(
            new RegisterOAuthClientCommand(
                organizationId,
                List.of(),
                OAuthClientDefaults.GRANT_TYPES,
                OAuthClientDefaults.SCOPES,
                OAuthClientDefaults.REQUIRE_CONSENT,
                List.of(),
                actor));
    return new ProvisionedOAuthClient(result.client().id(), result.client().clientId());
  }
}

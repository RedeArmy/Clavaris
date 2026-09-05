package com.clavaris.clientregistry.application.usecase.setredirectpolicyforclient;

import com.clavaris.clientregistry.application.usecase.registeroauthclient.OAuthClientRepository;
import com.clavaris.clientregistry.domain.model.OAuthClient;
import com.clavaris.clientregistry.domain.model.RedirectPolicy;
import com.clavaris.common.application.port.AuditEventRecorder;
import java.util.List;
import java.util.Objects;
import org.springframework.transaction.annotation.Transactional;

/**
 * Operator-managed only in v1, same posture as every other per-client/per-Organization policy
 * surface (ADR-0010 §6.2's own {@code SetRateLimitPolicyForOrganizationService} precedent). Every
 * non-null configured URL must be a verbatim member of the target {@code OAuthClient}'s own {@code
 * redirectUris} allowlist — enforced here, once, since it's the only place that has both the
 * command's URLs and the owning client's own field at the same time (see {@link
 * RedirectUrlNotRegisteredException}'s own Javadoc).
 */
public class SetRedirectPolicyForClientService implements SetRedirectPolicyForClientUseCase {

  private final OAuthClientRepository oauthClients;
  private final RedirectPolicyRepository policies;
  private final AuditEventRecorder auditEvents;

  public SetRedirectPolicyForClientService(
      final OAuthClientRepository oauthClients,
      final RedirectPolicyRepository policies,
      final AuditEventRecorder auditEvents) {
    this.oauthClients = oauthClients;
    this.policies = policies;
    this.auditEvents = auditEvents;
  }

  @Override
  @Transactional
  public SetRedirectPolicyForClientResult handle(final SetRedirectPolicyForClientCommand command) {
    final OAuthClient client =
        oauthClients
            .findById(command.oauthClientId())
            .filter(found -> found.organizationId().equals(command.organizationId()))
            .orElseThrow(() -> new OAuthClientNotFoundException(command.oauthClientId()));

    requireRegisteredIfPresent(client, command.fallbackSignInRedirectUrl());
    requireRegisteredIfPresent(client, command.fallbackSignUpRedirectUrl());
    requireRegisteredIfPresent(client, command.forceSignInRedirectUrl());
    requireRegisteredIfPresent(client, command.forceSignUpRedirectUrl());

    // Update in place if a policy already exists (an operator re-tuning it), define a fresh one
    // otherwise — same "define vs. update in place" shape as
    // SetRateLimitPolicyForOrganizationService.
    final RedirectPolicy policy =
        policies
            .findByOAuthClientId(command.oauthClientId())
            .map(
                existing ->
                    existing.withUrls(
                        command.fallbackSignInRedirectUrl(),
                        command.fallbackSignUpRedirectUrl(),
                        command.forceSignInRedirectUrl(),
                        command.forceSignUpRedirectUrl()))
            .orElseGet(
                () ->
                    RedirectPolicy.define(
                        command.oauthClientId(),
                        command.fallbackSignInRedirectUrl(),
                        command.fallbackSignUpRedirectUrl(),
                        command.forceSignInRedirectUrl(),
                        command.forceSignUpRedirectUrl()));

    policies.save(policy);

    auditEvents.write(
        command.actor(),
        "redirect_policy.set",
        "OAuthClient",
        command.oauthClientId().toString(),
        "organizationId=" + command.organizationId());

    return new SetRedirectPolicyForClientResult(policy);
  }

  private void requireRegisteredIfPresent(final OAuthClient client, final String url) {
    if (url == null) {
      return;
    }
    final List<String> redirectUris = client.redirectUris();
    if (redirectUris.stream().noneMatch(registered -> Objects.equals(registered, url))) {
      throw new RedirectUrlNotRegisteredException(url);
    }
  }
}

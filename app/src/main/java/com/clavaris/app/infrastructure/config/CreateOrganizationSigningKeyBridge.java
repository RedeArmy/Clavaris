package com.clavaris.app.infrastructure.config;

import com.clavaris.identity.application.usecase.activatesigningkeyfororganization.ActivateSigningKeyForOrganizationUseCase;
import com.clavaris.identity.domain.model.OrganizationId;
import com.clavaris.identity.domain.model.SigningKey;
import com.clavaris.identity.infrastructure.adapter.out.security.OrganizationSigningKeyMaterialFactory;
import com.clavaris.organization.application.usecase.createorganization.SigningKeyProvisioner;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Adapts organization-module's {@link SigningKeyProvisioner} outbound port to identity-module's
 * real key-generation/activation machinery — the bridge lives in {@code app}, not either business
 * module, because it needs both at once and {@code app} is the one module allowed to (CLAUDE.md
 * §7.2's module-graph rule).
 *
 * <p>Landed in this PR (not the later platform-tier composition PR) because organization-module's
 * own {@code OrganizationUseCaseConfig} eagerly wires a {@code CreateOrganizationUseCase} bean that
 * requires a {@code SigningKeyProvisioner} — confirmed live on CI: without this class present
 * somewhere on {@code app}'s classpath, {@code app}'s pre-existing full-context tests (which
 * predate this feature) failed to start at all, not just this feature's own tests. Deferring this
 * bridge to a separate PR would have meant "organization-module's own PR breaks master's existing
 * tests until a second, unrelated PR also lands" — not an acceptable intermediate state for a
 * trunk-based workflow (git-workflow.md §1: every PR keeps {@code master} deployable).
 */
@Component
class CreateOrganizationSigningKeyBridge implements SigningKeyProvisioner {

  // ADR-0002 — the only algorithm this codebase issues signing keys under, platform or
  // per-Organization.
  private static final String ALGORITHM = "RS256";

  private final ActivateSigningKeyForOrganizationUseCase keyActivator;
  private final OrganizationSigningKeyMaterialFactory materialFactory;

  /* package */ CreateOrganizationSigningKeyBridge(
      final ActivateSigningKeyForOrganizationUseCase keyActivator,
      final OrganizationSigningKeyMaterialFactory materialFactory) {
    this.keyActivator = keyActivator;
    this.materialFactory = materialFactory;
  }

  @Override
  public ProvisionedSigningKey provisionFor(final UUID organizationId) {
    final OrganizationId orgId = new OrganizationId(organizationId);
    // Generate the real key material first, then record it as active (BR-ORG-06) — the reverse
    // order would leave a metadata row claiming an active key that doesn't actually exist yet.
    final String kid = materialFactory.generateFor(orgId);
    final SigningKey activated = keyActivator.handle(orgId, kid, ALGORITHM);
    return new ProvisionedSigningKey(activated.id(), activated.kid(), activated.algorithm());
  }
}

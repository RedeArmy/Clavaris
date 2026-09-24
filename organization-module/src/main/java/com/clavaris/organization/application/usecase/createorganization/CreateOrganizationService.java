package com.clavaris.organization.application.usecase.createorganization;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.organization.application.usecase.setratelimitpolicyfororganization.RateLimitPolicyRepository;
import com.clavaris.organization.domain.model.Organization;
import com.clavaris.organization.domain.model.OrganizationEnvironment;
import com.clavaris.organization.domain.model.RateLimitPolicy;
import org.springframework.transaction.annotation.Transactional;

/**
 * BR-ORG-06: creates the {@code Organization} row and provisions its initial {@code SigningKey} AND
 * default {@code OAuthClient} synchronously, in the same operation — an Organization that exists
 * but cannot yet issue a token, or has no client an owner can actually use, is never allowed to be
 * an observable state. {@code @Transactional} covers this module's own {@code Organization} write,
 * identity-module's {@code SigningKey} write ({@link SigningKeyProvisioner}), and
 * client-registry-module's {@code OAuthClient} write ({@link OAuthClientProvisioner}): this is a
 * modular monolith on one database, so all three writes share the same connection/transaction
 * manager despite crossing module boundaries — true atomicity, not a best-effort saga.
 *
 * <p><b>SDE-III refactor, 2026-09-23 (Clerk-parity self-service simplification):</b> registering an
 * Organization's first real {@code OAuthClient} used to be a separate, subsequent, manual action —
 * an owner had to understand OAuth2 grant types/scopes/consent well enough to fill in a form before
 * their Organization could authenticate anything. The default client now registers itself here,
 * with {@code OAuthClientDefaults}' fixed grants/scopes/consent and no redirect URI yet (the owner
 * adds that afterward, the one field this flow can't fill in on their behalf) — amends this class's
 * own previously-documented "separate action" rule.
 *
 * <p>TD-SEC-007: also writes the {@code organization.created} audit event in this same transaction
 * — this is the exact action the technical-debt register named as unaudited ("Every platform-tier
 * action taken today (organization creation...)"), whether reached via the operator REST path or
 * the self-service dashboard (ADR-0012).
 *
 * <p><b>SDE-III feature build, 2026-09-04 (Clerk Development/Production instances analysis):</b>
 * {@link Organization#register} now always constructs a {@code DEVELOPMENT} Organization (see its
 * own Javadoc) — this class provisions a real, explicit {@link RateLimitPolicy} row for it, at a
 * much lower default than the system-wide one, so a brand-new sandbox can never accidentally absorb
 * production-scale traffic before an operator has deliberately promoted it. This is the one case
 * where this class DOES create a policy row up front — every other Organization (every already-
 * existing row, and every {@code PRODUCTION} sibling {@code CreateProductionEnvironmentService}
 * creates) still gets none, unchanged: a missing row still means "use the system default," exactly
 * as before this feature existed.
 */
@SuppressWarnings("PMD.LongVariable")
public class CreateOrganizationService implements CreateOrganizationUseCase {

  private final OrganizationRepository organizations;
  private final SigningKeyProvisioner keyProvisioner;
  private final OAuthClientProvisioner oauthClientProvisioner;
  private final PlatformAccountExistsChecker platformAccountExistsChecker;
  private final AuditEventRecorder auditEvents;
  private final RateLimitPolicyRepository policies;
  private final int developmentDefaultRequestsPerMinute;
  private final int hardSystemWideCap;

  @SuppressWarnings("java:S107") // one parameter per collaborating port/config value — same
  // rationale as every other orchestration-shaped constructor in this codebase.
  public CreateOrganizationService(
      final OrganizationRepository organizations,
      final SigningKeyProvisioner keyProvisioner,
      final OAuthClientProvisioner oauthClientProvisioner,
      final PlatformAccountExistsChecker platformAccountExistsChecker,
      final AuditEventRecorder auditEvents,
      final RateLimitPolicyRepository policies,
      final int developmentDefaultRequestsPerMinute,
      final int hardSystemWideCap) {
    this.organizations = organizations;
    this.keyProvisioner = keyProvisioner;
    this.oauthClientProvisioner = oauthClientProvisioner;
    this.platformAccountExistsChecker = platformAccountExistsChecker;
    this.auditEvents = auditEvents;
    this.policies = policies;
    this.developmentDefaultRequestsPerMinute = developmentDefaultRequestsPerMinute;
    this.hardSystemWideCap = hardSystemWideCap;
  }

  @Override
  @Transactional
  public CreateOrganizationResult handle(final CreateOrganizationCommand command) {
    // Security finding (SDE-III review, 2026-08-22): ownerPlatformAccountId used to be trusted
    // as-is — on the dashboard path it always comes from a real authenticated session, but the
    // REST/operator path (CreateOrganizationController) accepts it as caller-supplied JSON with
    // only @NotNull validation. The migration's own comment claims this is "enforced at the
    // application layer only" — this check is that enforcement; before it existed, that claim was
    // false and any UUID, real account or not, produced a real Organization with a real signing
    // key.
    if (!platformAccountExistsChecker.exists(command.ownerPlatformAccountId())) {
      throw new PlatformAccountNotFoundException(command.ownerPlatformAccountId());
    }

    final Organization organization =
        Organization.register(command.name(), command.ownerPlatformAccountId());
    // TD-PERF-019: insert, not save — Organization.register always mints a brand-new aggregate.
    // See OrganizationRepository#insert's own Javadoc.
    organizations.insert(organization);

    // See this class's own Javadoc: every new Organization is DEVELOPMENT by construction, so this
    // branch is unconditional today — written as a real check anyway (not asserted/assumed) since
    // Organization.register()'s own default is exactly the kind of thing a future change could
    // alter without this class noticing.
    if (organization.environment() == OrganizationEnvironment.DEVELOPMENT) {
      policies.save(
          RateLimitPolicy.define(
              organization.id(), developmentDefaultRequestsPerMinute, hardSystemWideCap));
    }

    final SigningKeyProvisioner.ProvisionedSigningKey signingKey =
        keyProvisioner.provisionFor(organization.id());
    final OAuthClientProvisioner.ProvisionedOAuthClient oauthClient =
        oauthClientProvisioner.provisionFor(organization.id(), command.actor());

    auditEvents.write(
        command.actor(),
        "organization.created",
        "Organization",
        organization.id().toString(),
        "ownerPlatformAccountId=" + command.ownerPlatformAccountId());

    return new CreateOrganizationResult(organization, signingKey, oauthClient);
  }
}

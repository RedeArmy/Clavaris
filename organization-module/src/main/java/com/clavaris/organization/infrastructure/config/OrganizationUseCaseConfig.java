package com.clavaris.organization.infrastructure.config;

import com.clavaris.common.application.port.AfterCommitExecutor;
import com.clavaris.common.application.port.AuditEventReader;
import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.organization.application.usecase.createorganization.CreateOrganizationService;
import com.clavaris.organization.application.usecase.createorganization.CreateOrganizationUseCase;
import com.clavaris.organization.application.usecase.createorganization.OAuthClientProvisioner;
import com.clavaris.organization.application.usecase.createorganization.OrganizationRepository;
import com.clavaris.organization.application.usecase.createorganization.PlatformAccountExistsChecker;
import com.clavaris.organization.application.usecase.createorganization.SigningKeyProvisioner;
import com.clavaris.organization.application.usecase.createproductionenvironment.CreateProductionEnvironmentService;
import com.clavaris.organization.application.usecase.createproductionenvironment.CreateProductionEnvironmentUseCase;
import com.clavaris.organization.application.usecase.deleteorganization.DeleteOrganizationService;
import com.clavaris.organization.application.usecase.deleteorganization.DeleteOrganizationUseCase;
import com.clavaris.organization.application.usecase.deleteorganization.EventOutboxWriter;
import com.clavaris.organization.application.usecase.deleteorganization.OrganizationIdentityDataEraser;
import com.clavaris.organization.application.usecase.deleteorganization.OrganizationOAuthClientsEraser;
import com.clavaris.organization.application.usecase.deleteorganization.OrganizationTokenRevoker;
import com.clavaris.organization.application.usecase.deleteorganization.OrganizationWebhookDataEraser;
import com.clavaris.organization.application.usecase.getauditlogfororganization.GetAuditLogForOrganizationService;
import com.clavaris.organization.application.usecase.getauditlogfororganization.GetAuditLogForOrganizationUseCase;
import com.clavaris.organization.application.usecase.getorganizationforplatformaccount.GetOrganizationForPlatformAccountService;
import com.clavaris.organization.application.usecase.getorganizationforplatformaccount.GetOrganizationForPlatformAccountUseCase;
import com.clavaris.organization.application.usecase.getorganizationlogo.GetOrganizationLogoService;
import com.clavaris.organization.application.usecase.getorganizationlogo.GetOrganizationLogoUseCase;
import com.clavaris.organization.application.usecase.getorganizationprofiles.GetOrganizationProfilesService;
import com.clavaris.organization.application.usecase.getorganizationprofiles.GetOrganizationProfilesUseCase;
import com.clavaris.organization.application.usecase.getratelimitpolicyfororganization.GetRateLimitPolicyForOrganizationService;
import com.clavaris.organization.application.usecase.getratelimitpolicyfororganization.GetRateLimitPolicyForOrganizationUseCase;
import com.clavaris.organization.application.usecase.getsessionpolicyfororganization.GetSessionPolicyForOrganizationService;
import com.clavaris.organization.application.usecase.getsessionpolicyfororganization.GetSessionPolicyForOrganizationUseCase;
import com.clavaris.organization.application.usecase.listorganizationsforplatformaccount.ListOrganizationsForPlatformAccountService;
import com.clavaris.organization.application.usecase.listorganizationsforplatformaccount.ListOrganizationsForPlatformAccountUseCase;
import com.clavaris.organization.application.usecase.listorganizationsforplatformaccountpaged.ListOrganizationsForPlatformAccountPagedService;
import com.clavaris.organization.application.usecase.listorganizationsforplatformaccountpaged.ListOrganizationsForPlatformAccountPagedUseCase;
import com.clavaris.organization.application.usecase.setratelimitpolicyfororganization.RateLimitPolicyRepository;
import com.clavaris.organization.application.usecase.setratelimitpolicyfororganization.SetRateLimitPolicyForOrganizationService;
import com.clavaris.organization.application.usecase.setratelimitpolicyfororganization.SetRateLimitPolicyForOrganizationUseCase;
import com.clavaris.organization.application.usecase.setsessionpolicyfororganization.SessionPolicyRepository;
import com.clavaris.organization.application.usecase.setsessionpolicyfororganization.SetSessionPolicyForOrganizationService;
import com.clavaris.organization.application.usecase.setsessionpolicyfororganization.SetSessionPolicyForOrganizationUseCase;
import com.clavaris.organization.application.usecase.updateorganizationprofile.OrganizationLogoRepository;
import com.clavaris.organization.application.usecase.updateorganizationprofile.OrganizationProfileRepository;
import com.clavaris.organization.application.usecase.updateorganizationprofile.UpdateOrganizationProfileService;
import com.clavaris.organization.application.usecase.updateorganizationprofile.UpdateOrganizationProfileUseCase;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the Organization lifecycle and its policies (create, profile, rate limit, session policy,
 * audit log, delete) to Spring's context — same rationale as identity-module's {@code
 * IdentityUseCaseConfig}. The Workspace and the security-settings use cases moved to their own
 * classes (TD-ARCH-042) so no single configuration class carries all 59 beans. Named with a module
 * prefix, not plain {@code UseCaseConfig}: confirmed live (client-registry-module's own {@code
 * ClientRegistryUseCaseConfig}) that the default class-name-derived bean name collides the moment
 * two modules sharing that plain name are on the same classpath.
 */
// Literals: the repeated string is "PMD.LongVariable" itself, reused across several @Bean
// method parameters that legitimately share the same port name — same false positive
// identity-module's own IdentityUseCaseConfig class-level suppression already documents.
// ExcessiveImports/CouplingBetweenObjects/TooManyMethods: this class's whole job is wiring one
// @Bean method per use case (this file's own doc comment) — the Workspace feature, then ADR-0022's
// social-credential use cases, each added more, tipping method/import/collaborator counts over
// PMD's default thresholds. Same "wiring, not sprawl" reasoning
// OrganizationAuthorizationServerConfig's
// own class-level Javadoc already documents for an identical situation, not worth splitting a class
// whose entire job is Spring bean assembly.
@SuppressWarnings({"PMD.ExcessiveImports", "PMD.CouplingBetweenObjects"})
@Configuration
class OrganizationUseCaseConfig {

  @SuppressWarnings("PMD.UnnecessaryConstructor")
  /* package */ OrganizationUseCaseConfig() {
    // Intentionally empty — this class holds no state, only the @Bean method below.
  }

  // SDE-III feature build, 2026-09-04: developmentDefaultRequestsPerMinute is deliberately much
  // lower than OrganizationCapacityRateLimitingFilter's own system default (600/min) — a brand-new
  // sandbox Organization should never accidentally absorb production-scale traffic before an
  // operator has deliberately promoted it (createProductionEnvironmentUseCase below). Reuses the
  // exact same hardSystemWideCap value setRateLimitPolicyForOrganizationUseCase already injects
  // below — one config source of truth for the one invariant RateLimitPolicy itself enforces.
  // java:S107 — one parameter per collaborating port/config value, same rationale as
  // CreateOrganizationService's own identical suppression.
  @SuppressWarnings({"PMD.LongVariable", "java:S107"})
  @Bean
  /* package */ CreateOrganizationUseCase createOrganizationUseCase(
      final OrganizationRepository organizations,
      final SigningKeyProvisioner keyProvisioner,
      final OAuthClientProvisioner oauthClientProvisioner,
      final PlatformAccountExistsChecker platformAccountExistsChecker,
      final AuditEventRecorder auditEvents,
      final RateLimitPolicyRepository policies,
      @Value("${clavaris.rate-limit.capacity.development-default-requests-per-minute:300}")
          final int developmentDefaultRequestsPerMinute,
      @Value("${clavaris.rate-limit.capacity.hard-cap-requests-per-minute:6000}")
          final int hardSystemWideCap) {
    return new CreateOrganizationService(
        organizations,
        keyProvisioner,
        oauthClientProvisioner,
        platformAccountExistsChecker,
        auditEvents,
        policies,
        developmentDefaultRequestsPerMinute,
        hardSystemWideCap);
  }

  // SDE-III feature build, 2026-09-04 (Clerk Development/Production instances analysis): promotes
  // a DEVELOPMENT Organization by creating its paired PRODUCTION sibling — see
  // CreateProductionEnvironmentService's own Javadoc for why this deliberately doesn't wrap
  // createOrganizationUseCase above rather than sharing its shape directly.
  @Bean
  /* package */ CreateProductionEnvironmentUseCase createProductionEnvironmentUseCase(
      final OrganizationRepository organizations,
      final SigningKeyProvisioner keyProvisioner,
      final AuditEventRecorder auditEvents) {
    return new CreateProductionEnvironmentService(organizations, keyProvisioner, auditEvents);
  }

  @Bean
  /* package */ ListOrganizationsForPlatformAccountUseCase
      listOrganizationsForPlatformAccountUseCase(final OrganizationRepository organizations) {
    return new ListOrganizationsForPlatformAccountService(organizations);
  }

  // TD-PERF-020: the dashboard's own paginated sibling — see that use case's own Javadoc for why
  // this is additive, not a replacement of the bean directly above.
  @Bean
  /* package */ ListOrganizationsForPlatformAccountPagedUseCase
      listOrganizationsForPlatformAccountPagedUseCase(final OrganizationRepository organizations) {
    return new ListOrganizationsForPlatformAccountPagedService(organizations);
  }

  // ADR-0025: the dashboard's own Organization-detail read.
  @Bean
  /* package */ GetOrganizationForPlatformAccountUseCase getOrganizationForPlatformAccountUseCase(
      final OrganizationRepository organizations) {
    return new GetOrganizationForPlatformAccountService(organizations);
  }

  // ADR-0010 §6.2: the hard system-wide cap no Organization's own RateLimitPolicy may ever
  // exceed — an operational value, not a domain constant, so it's configured here rather than
  // hardcoded in RateLimitPolicy itself. Default (6000/min = 100 req/s) is 10x
  // OrganizationCapacityRateLimitingFilter's own system default ceiling (600/min) — real headroom
  // to tune up a legitimately large tenant without the domain-level check ever being the actual
  // bottleneck.
  //
  // PMD.LinguisticNaming: this bean's name matches SetRateLimitPolicyForOrganizationUseCase
  // itself (lowercased first letter), the same convention every other @Bean method in this class
  // follows — it starts with "set" because the *use case* is named "Set...", not because this is
  // a JavaBean setter PMD's own naming check assumes it is.
  @SuppressWarnings({"PMD.LinguisticNaming"})
  @Bean
  /* package */ SetRateLimitPolicyForOrganizationUseCase setRateLimitPolicyForOrganizationUseCase(
      final OrganizationRepository organizations,
      final RateLimitPolicyRepository policies,
      @Value("${clavaris.rate-limit.capacity.hard-cap-requests-per-minute:6000}")
          final int hardSystemWideCap,
      final AuditEventRecorder auditEvents) {
    return new SetRateLimitPolicyForOrganizationService(
        organizations, policies, hardSystemWideCap, auditEvents);
  }

  // ADR-0025: the dashboard's own Rate Limit display — same
  // clavaris.rate-limit.capacity.default-requests-per-minute value
  // OrganizationCapacityRateLimitingFilter (app) already enforces, one config source of truth for
  // what "no override" actually means. TD-FUT-002 (self-service tuning, shipped): also injects
  // the same hardSystemWideCap value setRateLimitPolicyForOrganizationUseCase below already
  // does — see RateLimitPolicySnapshot's own Javadoc for why this read side now needs it too.
  @Bean
  /* package */ GetRateLimitPolicyForOrganizationUseCase getRateLimitPolicyForOrganizationUseCase(
      final RateLimitPolicyRepository policies,
      @Value("${clavaris.rate-limit.capacity.default-requests-per-minute:600}")
          final int systemDefaultRequestsPerMinute,
      @Value("${clavaris.rate-limit.capacity.hard-cap-requests-per-minute:6000}")
          final int hardSystemWideCap) {
    return new GetRateLimitPolicyForOrganizationService(
        policies, systemDefaultRequestsPerMinute, hardSystemWideCap);
  }

  // The Organization's own profile (description, application name, brand colour, logo), edited from
  // the dashboard's "Your organizations" cards and read by the consumer-facing branding.
  @Bean
  /* package */ UpdateOrganizationProfileUseCase updateOrganizationProfileUseCase(
      final OrganizationRepository organizations,
      final OrganizationProfileRepository profiles,
      final OrganizationLogoRepository logos,
      final AuditEventRecorder auditEvents) {
    return new UpdateOrganizationProfileService(organizations, profiles, logos, auditEvents);
  }

  @Bean
  /* package */ GetOrganizationProfilesUseCase getOrganizationProfilesUseCase(
      final OrganizationProfileRepository profiles) {
    return new GetOrganizationProfilesService(profiles);
  }

  @Bean
  /* package */ GetOrganizationLogoUseCase getOrganizationLogoUseCase(
      final OrganizationLogoRepository logos) {
    return new GetOrganizationLogoService(logos);
  }

  // Clerk "Sessions" settings parity — same dual-caller shape (REST admin API +
  // PlatformSessionPolicyController dashboard) as the rate-limit policy pair above.
  @SuppressWarnings("PMD.LinguisticNaming")
  @Bean
  /* package */ SetSessionPolicyForOrganizationUseCase setSessionPolicyForOrganizationUseCase(
      final OrganizationRepository organizations,
      final SessionPolicyRepository policies,
      final AuditEventRecorder auditEvents) {
    return new SetSessionPolicyForOrganizationService(organizations, policies, auditEvents);
  }

  @Bean
  /* package */ GetSessionPolicyForOrganizationUseCase getSessionPolicyForOrganizationUseCase(
      final SessionPolicyRepository policies) {
    return new GetSessionPolicyForOrganizationService(policies);
  }

  // ADR-0025, TD-SEC-007: the dashboard's own audit-log query. Reads by the tenant key the audit
  // table carries (TD-ARCH-038), one indexed page at a time.
  @Bean
  /* package */ GetAuditLogForOrganizationUseCase getAuditLogForOrganizationUseCase(
      final AuditEventReader auditEvents) {
    return new GetAuditLogForOrganizationService(auditEvents);
  }

  @Bean
  /* package */ DeleteOrganizationUseCase deleteOrganizationUseCase(
      final OrganizationRepository organizations,
      final OrganizationTokenRevoker organizationTokenRevoker,
      final OrganizationIdentityDataEraser identityDataEraser,
      final OrganizationOAuthClientsEraser oauthClientsEraser,
      final OrganizationWebhookDataEraser webhookDataEraser,
      final AuditEventRecorder auditEvents,
      final EventOutboxWriter eventOutboxWriter,
      final AfterCommitExecutor afterCommit) {
    return new DeleteOrganizationService(
        organizations,
        organizationTokenRevoker,
        identityDataEraser,
        oauthClientsEraser,
        webhookDataEraser,
        auditEvents,
        eventOutboxWriter,
        afterCommit);
  }
}

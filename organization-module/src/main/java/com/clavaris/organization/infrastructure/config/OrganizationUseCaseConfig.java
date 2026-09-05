package com.clavaris.organization.infrastructure.config;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.organization.application.usecase.addworkspacemember.AccountProvisioner;
import com.clavaris.organization.application.usecase.addworkspacemember.AddWorkspaceMemberService;
import com.clavaris.organization.application.usecase.addworkspacemember.AddWorkspaceMemberUseCase;
import com.clavaris.organization.application.usecase.addworkspacemember.WorkspaceMembershipRepository;
import com.clavaris.organization.application.usecase.changeworkspacememberrole.ChangeWorkspaceMemberRoleService;
import com.clavaris.organization.application.usecase.changeworkspacememberrole.ChangeWorkspaceMemberRoleUseCase;
import com.clavaris.organization.application.usecase.createorganization.CreateOrganizationService;
import com.clavaris.organization.application.usecase.createorganization.CreateOrganizationUseCase;
import com.clavaris.organization.application.usecase.createorganization.OrganizationRepository;
import com.clavaris.organization.application.usecase.createorganization.PlatformAccountExistsChecker;
import com.clavaris.organization.application.usecase.createorganization.SigningKeyProvisioner;
import com.clavaris.organization.application.usecase.createproductionenvironment.CreateProductionEnvironmentService;
import com.clavaris.organization.application.usecase.createproductionenvironment.CreateProductionEnvironmentUseCase;
import com.clavaris.organization.application.usecase.createworkspace.CreateWorkspaceService;
import com.clavaris.organization.application.usecase.createworkspace.CreateWorkspaceUseCase;
import com.clavaris.organization.application.usecase.createworkspace.WorkspaceRepository;
import com.clavaris.organization.application.usecase.deleteorganization.DeleteOrganizationService;
import com.clavaris.organization.application.usecase.deleteorganization.DeleteOrganizationUseCase;
import com.clavaris.organization.application.usecase.deleteorganization.EventOutboxWriter;
import com.clavaris.organization.application.usecase.deleteorganization.OrganizationIdentityDataEraser;
import com.clavaris.organization.application.usecase.deleteorganization.OrganizationOAuthClientsEraser;
import com.clavaris.organization.application.usecase.deleteorganization.OrganizationTokenRevoker;
import com.clavaris.organization.application.usecase.deleteorganizationsocialcredential.DeleteOrganizationSocialCredentialService;
import com.clavaris.organization.application.usecase.deleteorganizationsocialcredential.DeleteOrganizationSocialCredentialUseCase;
import com.clavaris.organization.application.usecase.getaccountauthenticationpolicyfororganization.GetAccountAuthenticationPolicyForOrganizationService;
import com.clavaris.organization.application.usecase.getaccountauthenticationpolicyfororganization.GetAccountAuthenticationPolicyForOrganizationUseCase;
import com.clavaris.organization.application.usecase.getorganizationapikeys.GetOrganizationApiKeysService;
import com.clavaris.organization.application.usecase.getorganizationapikeys.GetOrganizationApiKeysUseCase;
import com.clavaris.organization.application.usecase.getorganizationapikeys.OrganizationSigningKeyPublicKeyProvider;
import com.clavaris.organization.application.usecase.listorganizationsforplatformaccount.ListOrganizationsForPlatformAccountService;
import com.clavaris.organization.application.usecase.listorganizationsforplatformaccount.ListOrganizationsForPlatformAccountUseCase;
import com.clavaris.organization.application.usecase.listorganizationsocialcredentials.ListOrganizationSocialCredentialsService;
import com.clavaris.organization.application.usecase.listorganizationsocialcredentials.ListOrganizationSocialCredentialsUseCase;
import com.clavaris.organization.application.usecase.listworkspacemembers.ListWorkspaceMembersService;
import com.clavaris.organization.application.usecase.listworkspacemembers.ListWorkspaceMembersUseCase;
import com.clavaris.organization.application.usecase.listworkspacesfororganization.ListWorkspacesForOrganizationService;
import com.clavaris.organization.application.usecase.listworkspacesfororganization.ListWorkspacesForOrganizationUseCase;
import com.clavaris.organization.application.usecase.removeworkspacemember.RemoveWorkspaceMemberService;
import com.clavaris.organization.application.usecase.removeworkspacemember.RemoveWorkspaceMemberUseCase;
import com.clavaris.organization.application.usecase.setaccountauthenticationpolicyfororganization.AccountAuthenticationPolicyRepository;
import com.clavaris.organization.application.usecase.setaccountauthenticationpolicyfororganization.SetAccountAuthenticationPolicyForOrganizationService;
import com.clavaris.organization.application.usecase.setaccountauthenticationpolicyfororganization.SetAccountAuthenticationPolicyForOrganizationUseCase;
import com.clavaris.organization.application.usecase.setorganizationsocialcredential.OrganizationSocialCredentialCipher;
import com.clavaris.organization.application.usecase.setorganizationsocialcredential.OrganizationSocialCredentialRepository;
import com.clavaris.organization.application.usecase.setorganizationsocialcredential.SetOrganizationSocialCredentialService;
import com.clavaris.organization.application.usecase.setorganizationsocialcredential.SetOrganizationSocialCredentialUseCase;
import com.clavaris.organization.application.usecase.setratelimitpolicyfororganization.RateLimitPolicyRepository;
import com.clavaris.organization.application.usecase.setratelimitpolicyfororganization.SetRateLimitPolicyForOrganizationService;
import com.clavaris.organization.application.usecase.setratelimitpolicyfororganization.SetRateLimitPolicyForOrganizationUseCase;
import com.clavaris.organization.application.usecase.setsocialloginpolicyfororganization.SetSocialLoginPolicyForOrganizationService;
import com.clavaris.organization.application.usecase.setsocialloginpolicyfororganization.SetSocialLoginPolicyForOrganizationUseCase;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Wires application-layer use cases to Spring's context — same rationale as identity-module's
 * {@code IdentityUseCaseConfig}. Named with a module prefix, not plain {@code UseCaseConfig}:
 * confirmed live (client-registry-module's own {@code ClientRegistryUseCaseConfig}) that the
 * default class-name-derived bean name collides the moment two modules sharing that plain name are
 * on the same classpath.
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
@SuppressWarnings({
  "PMD.AvoidDuplicateLiterals",
  "PMD.ExcessiveImports",
  "PMD.CouplingBetweenObjects",
  "PMD.TooManyMethods"
})
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
  @SuppressWarnings("PMD.LongVariable")
  @Bean
  /* package */ CreateOrganizationUseCase createOrganizationUseCase(
      final OrganizationRepository organizations,
      final SigningKeyProvisioner keyProvisioner,
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

  @Bean
  /* package */ DeleteOrganizationUseCase deleteOrganizationUseCase(
      final OrganizationRepository organizations,
      @SuppressWarnings("PMD.LongVariable") final OrganizationTokenRevoker organizationTokenRevoker,
      @SuppressWarnings("PMD.LongVariable") final OrganizationIdentityDataEraser identityDataEraser,
      @SuppressWarnings("PMD.LongVariable") final OrganizationOAuthClientsEraser oauthClientsEraser,
      final AuditEventRecorder auditEvents,
      final EventOutboxWriter eventOutboxWriter) {
    return new DeleteOrganizationService(
        organizations,
        organizationTokenRevoker,
        identityDataEraser,
        oauthClientsEraser,
        auditEvents,
        eventOutboxWriter);
  }

  @Bean
  /* package */ CreateWorkspaceUseCase createWorkspaceUseCase(
      final WorkspaceRepository workspaces,
      final OrganizationRepository organizations,
      final AuditEventRecorder auditEvents,
      final EventOutboxWriter eventOutboxWriter) {
    return new CreateWorkspaceService(workspaces, organizations, auditEvents, eventOutboxWriter);
  }

  // AddWorkspaceMemberService's own Javadoc explains why this needs a real TransactionTemplate,
  // not @Transactional on the service method itself: it calls AccountProvisioner (a real
  // cross-module write + network mail send) between its own read and its own transactional write,
  // and @Transactional on the whole method would hold a DB transaction open across that call.
  @Bean
  /* package */ AddWorkspaceMemberUseCase addWorkspaceMemberUseCase(
      final WorkspaceRepository workspaces,
      final WorkspaceMembershipRepository memberships,
      @SuppressWarnings("PMD.LongVariable") final AccountProvisioner accountProvisioner,
      final AuditEventRecorder auditEvents,
      final EventOutboxWriter eventOutboxWriter,
      @SuppressWarnings("PMD.LongVariable") final PlatformTransactionManager transactionManager) {
    return new AddWorkspaceMemberService(
        workspaces,
        memberships,
        accountProvisioner,
        auditEvents,
        eventOutboxWriter,
        new TransactionTemplate(transactionManager));
  }

  @Bean
  /* package */ ChangeWorkspaceMemberRoleUseCase changeWorkspaceMemberRoleUseCase(
      final WorkspaceMembershipRepository memberships,
      final WorkspaceRepository workspaces,
      final AuditEventRecorder auditEvents,
      final EventOutboxWriter eventOutboxWriter) {
    return new ChangeWorkspaceMemberRoleService(
        memberships, workspaces, auditEvents, eventOutboxWriter);
  }

  @Bean
  /* package */ RemoveWorkspaceMemberUseCase removeWorkspaceMemberUseCase(
      final WorkspaceMembershipRepository memberships,
      final WorkspaceRepository workspaces,
      final AuditEventRecorder auditEvents,
      final EventOutboxWriter eventOutboxWriter) {
    return new RemoveWorkspaceMemberService(
        memberships, workspaces, auditEvents, eventOutboxWriter);
  }

  @Bean
  /* package */ ListWorkspacesForOrganizationUseCase listWorkspacesForOrganizationUseCase(
      final WorkspaceRepository workspaces) {
    return new ListWorkspacesForOrganizationService(workspaces);
  }

  @Bean
  /* package */ ListWorkspaceMembersUseCase listWorkspaceMembersUseCase(
      final WorkspaceMembershipRepository memberships) {
    return new ListWorkspaceMembersService(memberships);
  }

  // PMD.LinguisticNaming: same false positive SetRateLimitPolicyForOrganizationUseCase's own
  // @Bean method already documents above — this bean's name mirrors the use case's own name.
  @SuppressWarnings({"PMD.LinguisticNaming"})
  @Bean
  /* package */ SetSocialLoginPolicyForOrganizationUseCase
      setSocialLoginPolicyForOrganizationUseCase(
          final OrganizationRepository organizations, final AuditEventRecorder auditEvents) {
    return new SetSocialLoginPolicyForOrganizationService(organizations, auditEvents);
  }

  // PMD.LinguisticNaming: same false positive SetRateLimitPolicyForOrganizationUseCase's own
  // @Bean method already documents above.
  @SuppressWarnings("PMD.LinguisticNaming")
  @Bean
  /* package */ SetOrganizationSocialCredentialUseCase setOrganizationSocialCredentialUseCase(
      final OrganizationRepository organizations,
      final OrganizationSocialCredentialRepository credentials,
      final OrganizationSocialCredentialCipher cipher,
      final AuditEventRecorder auditEvents) {
    return new SetOrganizationSocialCredentialService(
        organizations, credentials, cipher, auditEvents);
  }

  @Bean
  /* package */ ListOrganizationSocialCredentialsUseCase listOrganizationSocialCredentialsUseCase(
      final OrganizationSocialCredentialRepository credentials) {
    return new ListOrganizationSocialCredentialsService(credentials);
  }

  @Bean
  /* package */ DeleteOrganizationSocialCredentialUseCase deleteOrganizationSocialCredentialUseCase(
      final OrganizationSocialCredentialRepository credentials,
      final AuditEventRecorder auditEvents) {
    return new DeleteOrganizationSocialCredentialService(credentials, auditEvents);
  }

  // ADR-0023 / Clerk API-keys parity
  @Bean
  /* package */ GetOrganizationApiKeysUseCase getOrganizationApiKeysUseCase(
      final OrganizationRepository organizations,
      final OrganizationSigningKeyPublicKeyProvider publicKeyProvider,
      @Value("${CLAVARIS_BASE_URL:http://localhost:8080}") final String clavarisBaseUrl) {
    return new GetOrganizationApiKeysService(organizations, publicKeyProvider, clavarisBaseUrl);
  }

  // ADR-0024 / Clerk sign-up-sign-in-options parity. PMD.LinguisticNaming: same false positive
  // SetRateLimitPolicyForOrganizationUseCase's own @Bean method already documents above.
  @SuppressWarnings("PMD.LinguisticNaming")
  @Bean
  /* package */ SetAccountAuthenticationPolicyForOrganizationUseCase
      setAccountAuthenticationPolicyForOrganizationUseCase(
          final OrganizationRepository organizations,
          final AccountAuthenticationPolicyRepository policies,
          final AuditEventRecorder auditEvents) {
    return new SetAccountAuthenticationPolicyForOrganizationService(
        organizations, policies, auditEvents);
  }

  @Bean
  /* package */ GetAccountAuthenticationPolicyForOrganizationUseCase
      getAccountAuthenticationPolicyForOrganizationUseCase(
          final AccountAuthenticationPolicyRepository policies) {
    return new GetAccountAuthenticationPolicyForOrganizationService(policies);
  }
}

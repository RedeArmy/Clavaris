package com.clavaris.app.infrastructure.adapter.out.bridge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.clavaris.app.infrastructure.adapter.in.web.ImpersonationClientNotFoundException;
import com.clavaris.app.infrastructure.adapter.in.web.ImpersonationScopeNotAllowedException;
import com.clavaris.app.infrastructure.adapter.out.security.ImpersonationTokenIssuer;
import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.identity.application.usecase.impersonateaccount.MintedImpersonationToken;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.OrganizationId;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * TD-SEC-011-shaped bridge test: proves the exception translation across the port boundary — the
 * one piece {@link ImpersonateAccountIntegrationTest} (a full REST-endpoint E2E) never exercises,
 * since the REST controller catches {@code app}'s own exceptions directly and never goes through
 * this bridge at all.
 */
class ImpersonationTokenMinterBridgeTest {

  private final ImpersonationTokenIssuer tokenIssuer = mock(ImpersonationTokenIssuer.class);
  private final ImpersonationTokenMinterBridge bridge =
      new ImpersonationTokenMinterBridge(tokenIssuer);

  private final AccountId accountId = new AccountId(UUID.randomUUID());
  private final OrganizationId organizationId = new OrganizationId(UUID.randomUUID());
  private final AuditActor actor = AuditActor.platformAccount(UUID.randomUUID());

  @Test
  void mapsTheIssuedTokenToTheIdentityModuleOwnedRecord() {
    ImpersonationTokenIssuer.ImpersonationToken issued =
        new ImpersonationTokenIssuer.ImpersonationToken(
            "raw-access-token", Instant.now().plusSeconds(300), Set.of("openid"));
    when(tokenIssuer.mint(
            eq(accountId), eq(organizationId), eq("jobseeker-web"), any(), eq(actor), any()))
        .thenReturn(issued);

    MintedImpersonationToken result =
        bridge.mint(
            accountId,
            organizationId,
            "jobseeker-web",
            List.of("openid"),
            actor,
            "http://localhost");

    assertThat(result)
        .isEqualTo(
            new MintedImpersonationToken(
                "raw-access-token", "Bearer", issued.expiresAt(), Set.of("openid")));
  }

  @Test
  void translatesAppsOwnClientNotFoundExceptionToIdentityModulesCopy() {
    when(tokenIssuer.mint(any(), any(), any(), any(), any(), any()))
        .thenThrow(new ImpersonationClientNotFoundException("unknown-client"));

    assertThatExceptionOfType(
            com.clavaris.identity.application.usecase.impersonateaccount
                .ImpersonationClientNotFoundException.class)
        .isThrownBy(
            () ->
                bridge.mint(
                    accountId,
                    organizationId,
                    "unknown-client",
                    List.of(),
                    actor,
                    "http://localhost"));
  }

  @Test
  void translatesAppsOwnScopeNotAllowedExceptionToIdentityModulesCopy() {
    when(tokenIssuer.mint(any(), any(), any(), any(), any(), any()))
        .thenThrow(new ImpersonationScopeNotAllowedException("jobseeker-web"));

    assertThatExceptionOfType(
            com.clavaris.identity.application.usecase.impersonateaccount
                .ImpersonationScopeNotAllowedException.class)
        .isThrownBy(
            () ->
                bridge.mint(
                    accountId,
                    organizationId,
                    "jobseeker-web",
                    List.of("admin"),
                    actor,
                    "http://localhost"));
  }
}

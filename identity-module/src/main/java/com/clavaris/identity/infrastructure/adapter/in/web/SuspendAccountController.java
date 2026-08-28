package com.clavaris.identity.infrastructure.adapter.in.web;

import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.identity.application.usecase.suspendaccount.AccountNotFoundException;
import com.clavaris.identity.application.usecase.suspendaccount.SuspendAccountCommand;
import com.clavaris.identity.application.usecase.suspendaccount.SuspendAccountUseCase;
import com.clavaris.identity.domain.model.AccountId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /api/v1/admin/accounts/{id}:suspend} — a reversible ban, distinct from {@code
 * DeleteAccountController}'s permanent hard delete. Same {@code :action}-on-resource naming
 * precedent, same operator/consumer-application-only tier, enforced by {@code
 * AdminApiSecurityConfig}.
 */
@RestController
class SuspendAccountController {

  private final SuspendAccountUseCase useCase;

  /* package */ SuspendAccountController(final SuspendAccountUseCase useCase) {
    this.useCase = useCase;
  }

  // Two exits (404 on an unknown id, 204 on success) — same rationale as DeleteAccountController's
  // own identical suppression.
  @SuppressWarnings({"PMD.OnlyOneReturn", "PMD.ShortVariable"})
  @Operation(summary = "Reversibly suspend/ban an Account")
  @ApiResponse(responseCode = "204", description = "Suspended — future logins rejected immediately")
  @ApiResponse(responseCode = "404", description = "No Account exists with the given id")
  @PostMapping("/api/v1/admin/accounts/{id}:suspend")
  /* package */ ResponseEntity<Void> suspend(
      @PathVariable final UUID id, final Authentication authentication) {
    try {
      useCase.handle(
          new SuspendAccountCommand(
              new AccountId(id), AuditActor.platformClient(authentication.getName())));
    } catch (final AccountNotFoundException _) {
      return ResponseEntity.notFound().build();
    }
    return ResponseEntity.noContent().build();
  }
}

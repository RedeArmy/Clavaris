package com.clavaris.identity.infrastructure.adapter.in.web;

import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.identity.application.usecase.forcepasswordresetforaccount.AccountNotFoundException;
import com.clavaris.identity.application.usecase.forcepasswordresetforaccount.ForcePasswordResetForAccountCommand;
import com.clavaris.identity.application.usecase.forcepasswordresetforaccount.ForcePasswordResetForAccountUseCase;
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
 * Clerk "session tasks" parity: {@code POST /api/v1/admin/accounts/{id}:force-password-reset} —
 * same {@code :action}-on-resource naming precedent as {@link SuspendAccountController}, same
 * operator/consuming-application-only tier, enforced by {@code AdminApiSecurityConfig}. Never
 * itself revokes anything — see {@code ForcePasswordResetForAccountService}'s own Javadoc for why.
 */
@RestController
class ForcePasswordResetForAccountController {

  private final ForcePasswordResetForAccountUseCase useCase;

  /* package */ ForcePasswordResetForAccountController(
      final ForcePasswordResetForAccountUseCase useCase) {
    this.useCase = useCase;
  }

  // Two exits (404 on an unknown id, 204 on success) — same rationale as SuspendAccountController's
  // own identical suppression.
  @SuppressWarnings({"PMD.OnlyOneReturn", "PMD.ShortVariable"})
  @Operation(summary = "Force an Account to set a new password before its next login can complete")
  @ApiResponse(responseCode = "204", description = "Marked — enforced by the account's next login")
  @ApiResponse(responseCode = "404", description = "No Account exists with the given id")
  @PostMapping("/api/v1/admin/accounts/{id}:force-password-reset")
  /* package */ ResponseEntity<Void> forceReset(
      @PathVariable final UUID id, final Authentication authentication) {
    try {
      useCase.handle(
          new ForcePasswordResetForAccountCommand(
              new AccountId(id), AuditActor.platformClient(authentication.getName())));
    } catch (final AccountNotFoundException _) {
      return ResponseEntity.notFound().build();
    }
    return ResponseEntity.noContent().build();
  }
}

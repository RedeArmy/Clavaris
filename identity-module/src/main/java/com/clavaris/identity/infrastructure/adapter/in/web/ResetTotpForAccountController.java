package com.clavaris.identity.infrastructure.adapter.in.web;

import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.identity.application.usecase.resettotpforaccount.ResetTotpForAccountCommand;
import com.clavaris.identity.application.usecase.resettotpforaccount.ResetTotpForAccountUseCase;
import com.clavaris.identity.application.usecase.suspendaccount.AccountNotFoundException;
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
 * ADR-0040: the operator's way back for an Account that lost both its authenticator app and its
 * recovery codes. Operator-only (its own scope), idempotent, and audited with the calling client.
 */
@RestController
public class ResetTotpForAccountController {

  private final ResetTotpForAccountUseCase useCase;

  public ResetTotpForAccountController(final ResetTotpForAccountUseCase useCase) {
    this.useCase = useCase;
  }

  // Two exits (404 on an unknown id, 204 on success) — same rationale as SuspendAccountController.
  @Operation(summary = "Remove an Account's authenticator app so it can sign in and add a new one")
  @ApiResponse(responseCode = "204", description = "Removed, or the Account had none")
  @ApiResponse(responseCode = "404", description = "No Account exists with the given id")
  @PostMapping("/api/v1/admin/accounts/{id}:reset-totp")
  public ResponseEntity<Void> reset(
      @PathVariable final UUID id, final Authentication authentication) {
    try {
      useCase.handle(
          new ResetTotpForAccountCommand(
              new AccountId(id), AuditActor.platformClient(authentication.getName())));
    } catch (final AccountNotFoundException _) {
      return ResponseEntity.notFound().build();
    }
    return ResponseEntity.noContent().build();
  }
}

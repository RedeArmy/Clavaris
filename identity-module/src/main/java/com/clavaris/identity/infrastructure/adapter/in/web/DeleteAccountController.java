package com.clavaris.identity.infrastructure.adapter.in.web;

import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.identity.application.usecase.deleteaccount.AccountNotFoundException;
import com.clavaris.identity.application.usecase.deleteaccount.DeleteAccountCommand;
import com.clavaris.identity.application.usecase.deleteaccount.DeleteAccountUseCase;
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
 * BR-DATA-02: {@code POST /api/v1/admin/accounts/{id}:delete} — the custom-method-on-a-resource
 * naming (`:delete`, not a plain {@code DELETE /api/v1/admin/accounts/{id}}) matches this project's
 * own existing precedent for a destructive action that isn't simple REST-resource deletion
 * (`api-contract-overview.md`), and is what `security-architecture.md` §7's own design already
 * committed to before this controller existed to implement it. Operator/consumer-application only,
 * never a tenant's own token — enforced by {@code app}'s own {@code AdminApiSecurityConfig}, same
 * separation of concerns as every other {@code /api/v1/admin/**} controller in this codebase.
 */
@RestController
class DeleteAccountController {

  private final DeleteAccountUseCase useCase;

  /* package */ DeleteAccountController(final DeleteAccountUseCase useCase) {
    this.useCase = useCase;
  }

  // Two exits (404 on an unknown id, 204 on success) — same rationale as
  // DeactivatePlatformClientController's own identical suppression. ShortVariable: "id" matches
  // the path variable's own name in the API contract (`{id}:delete`, security-architecture.md
  // §7) — same precedent as RegisteredClientRepository's own SPI parameter naming elsewhere.
  @SuppressWarnings({"PMD.OnlyOneReturn", "PMD.ShortVariable"})
  @Operation(summary = "Hard-delete an Account and everything only it owns (BR-DATA-02/03)")
  @ApiResponse(responseCode = "204", description = "Deleted — permanent, not reversible")
  @ApiResponse(responseCode = "404", description = "No Account exists with the given id")
  @PostMapping("/api/v1/admin/accounts/{id}:delete")
  /* package */ ResponseEntity<Void> delete(
      @PathVariable final UUID id, final Authentication authentication) {
    try {
      useCase.handle(
          new DeleteAccountCommand(
              new AccountId(id), AuditActor.platformClient(authentication.getName())));
    } catch (final AccountNotFoundException _) {
      return ResponseEntity.notFound().build();
    }
    return ResponseEntity.noContent().build();
  }
}

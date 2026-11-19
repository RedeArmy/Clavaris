package com.clavaris.identity.infrastructure.adapter.in.web;

import com.clavaris.identity.application.usecase.getaccountstatus.GetAccountStatusQuery;
import com.clavaris.identity.application.usecase.getaccountstatus.GetAccountStatusUseCase;
import com.clavaris.identity.domain.model.AccountId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * TD-FUT-081 / ADR-0047: {@code GET /api/v1/admin/accounts/{id}/status}. The answer a consumer can
 * ask for as often as it likes, to know whether an Account is still allowed, instead of spending a
 * refresh token (which rotates it) or waiting for a webhook (which can be lost or arrive out of
 * order). An Organization's Secret Key reaches it for its own Organization only (the ownership
 * filter), behind its own scope.
 *
 * <p>{@code no-store}: a cached status defeats the point of asking.
 */
// BR-ID-35: an Organization's Secret Key reads its own Accounts' status and nothing else.
@RestController
class GetAccountStatusController {

  private final GetAccountStatusUseCase useCase;

  /* package */ GetAccountStatusController(final GetAccountStatusUseCase useCase) {
    this.useCase = useCase;
  }

  @Operation(
      summary = "Read an Account's status: ACTIVE, SUSPENDED, BANNED, PENDING_APPROVAL, REJECTED")
  @ApiResponse(responseCode = "200", description = "Status returned, with no personal data")
  @ApiResponse(
      responseCode = "403",
      description =
          "For an Organization's Secret Key: not an Account of its own Organization, which includes"
              + " one that was erased (the same answer for every case, so it is no oracle)")
  @ApiResponse(
      responseCode = "404",
      description = "For a platform credential: no Account exists with the given id")
  @GetMapping("/api/v1/admin/accounts/{id}/status")
  /* package */ ResponseEntity<AccountStatusResponse> get(@PathVariable final UUID id) {
    final AccountId accountId = new AccountId(id);
    return useCase
        .handle(new GetAccountStatusQuery(accountId))
        .map(status -> AccountStatusResponse.of(accountId, status))
        .map(response -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(response))
        .orElseGet(() -> ResponseEntity.notFound().build());
  }
}

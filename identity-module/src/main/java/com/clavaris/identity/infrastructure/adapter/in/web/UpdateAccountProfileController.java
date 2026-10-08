package com.clavaris.identity.infrastructure.adapter.in.web;

import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.identity.application.usecase.getaccountprofile.GetAccountProfileQuery;
import com.clavaris.identity.application.usecase.getaccountprofile.GetAccountProfileUseCase;
import com.clavaris.identity.application.usecase.registeraccount.UsernameAlreadyRegisteredException;
import com.clavaris.identity.application.usecase.updateaccountprofile.AccountNotFoundException;
import com.clavaris.identity.application.usecase.updateaccountprofile.UpdateAccountProfileCommand;
import com.clavaris.identity.application.usecase.updateaccountprofile.UpdateAccountProfileUseCase;
import com.clavaris.identity.domain.model.Account;
import com.clavaris.identity.domain.model.AccountId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * TD-FUT-041: {@code PUT /api/v1/admin/accounts/{id}/profile} — Backend-API-style profile write for
 * a consuming application's own backend, Clerk {@code clerkClient.users.updateUser} parity. Reuses
 * {@link UpdateAccountProfileUseCase} exactly as it already stood (built for {@code
 * AccountProfileController}'s self-service caller and {@code
 * PlatformAccountProfileAdminController}'s operator-driven one, TD-FUT-040) — this is its third
 * caller, not a fourth implementation. Same dual-authority reachability as {@link
 * GetAccountProfileController}; see that class's own Javadoc for the scope this shares with it.
 *
 * <p><b>Validation finding, same day:</b> {@code Account.updateProfile(firstName, lastName)} is a
 * deliberate, unconditional overwrite by design (that method's own Javadoc: "not Optional-typed
 * parameters... a blank submitted value is this method's own caller's job to normalize to null
 * before calling"). The two existing callers are safe only because their own HTML forms always
 * resubmit the current value for every field, every time — a raw JSON caller (this class) has no
 * such form, so a consumer sending {@code {"username": "x"}} alone would otherwise silently wipe an
 * existing firstName/lastName as a side effect of only trying to set a username, directly
 * contradicting {@link UpdateAccountProfileRequest}'s own "omitted means leave unconfigured, never
 * cleared to blank" contract. Resolved here, at this boundary, by fetching the current Account
 * first and substituting its existing firstName/lastName wherever the request left one unspecified
 * — deliberately NOT a change to {@code UpdateAccountProfileService}'s own shared behavior, which
 * the other two callers' own tests already rely on staying exactly as it is.
 */
// PMD.OnlyOneReturn: four genuinely distinct exits (404 unknown account on the initial read, 404
// on the update itself — the same real TOCTOU-safe defensive catch
// UpdateAccountMetadataController's
// own identical suppression documents, 409 username conflict, 204 success).
// PMD.ShortVariable: id matches the path variable's own wire name (and
// UpdateAccountMetadataController's own identically-named parameter) — not an organically short
// name that should grow.
@SuppressWarnings({"PMD.OnlyOneReturn", "PMD.ShortVariable"})
@RestController
class UpdateAccountProfileController {

  private final GetAccountProfileUseCase getProfile;
  private final UpdateAccountProfileUseCase useCase;

  /* package */ UpdateAccountProfileController(
      final GetAccountProfileUseCase getProfile, final UpdateAccountProfileUseCase useCase) {
    this.getProfile = getProfile;
    this.useCase = useCase;
  }

  @Operation(summary = "Update an Account's firstName/lastName/username/phoneNumber (TD-FUT-041)")
  @ApiResponse(responseCode = "204", description = "Profile updated")
  @ApiResponse(responseCode = "404", description = "No Account exists with the given id")
  @ApiResponse(responseCode = "409", description = "The submitted username is already taken")
  @PutMapping("/api/v1/admin/accounts/{id}/profile")
  /* package */ ResponseEntity<Void> update(
      @PathVariable final UUID id,
      @RequestBody final UpdateAccountProfileRequest request,
      final Authentication authentication) {
    final AccountId accountId = new AccountId(id);
    final Account current = getProfile.handle(new GetAccountProfileQuery(accountId)).orElse(null);
    if (current == null) {
      return ResponseEntity.notFound().build();
    }
    try {
      useCase.handle(
          new UpdateAccountProfileCommand(
              accountId,
              request.firstName() != null ? request.firstName() : current.firstName().orElse(null),
              request.lastName() != null ? request.lastName() : current.lastName().orElse(null),
              request.username(),
              request.phoneNumber(),
              AuditActor.platformClient(authentication.getName())));
    } catch (final AccountNotFoundException _) {
      // TOCTOU: the Account existed on the read above but was deleted before this write landed —
      // rare, still a real possible outcome, not an unhandled 500.
      return ResponseEntity.notFound().build();
    } catch (final UsernameAlreadyRegisteredException _) {
      return ResponseEntity.status(HttpStatus.CONFLICT).build();
    }
    return ResponseEntity.noContent().build();
  }
}

package com.clavaris.app.infrastructure.adapter.out.bridge;

import com.clavaris.app.infrastructure.adapter.out.security.ImpersonationTokenIssuer;
import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.identity.application.usecase.impersonateaccount.ImpersonationClientNotFoundException;
import com.clavaris.identity.application.usecase.impersonateaccount.ImpersonationScopeNotAllowedException;
import com.clavaris.identity.application.usecase.impersonateaccount.ImpersonationTokenMinter;
import com.clavaris.identity.application.usecase.impersonateaccount.MintedImpersonationToken;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.OrganizationId;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Adapts {@code app}'s own {@link ImpersonationTokenIssuer} — the same one {@code
 * ImpersonateAccountController}'s REST endpoint already uses — to identity-module's own {@link
 * ImpersonationTokenMinter} port, translating {@code app}'s own impersonation exceptions to
 * identity-module's identically-named copies (see that port's own Javadoc for why two copies
 * exist). {@code ImpersonationTokenGenerationFailedException} is deliberately left uncaught — the
 * dashboard modal treats it the same "infrastructure problem, not caller input" way the REST
 * endpoint's own {@code GlobalExceptionHandler} catch-all does.
 */
@Component
class ImpersonationTokenMinterBridge implements ImpersonationTokenMinter {

  private final ImpersonationTokenIssuer tokenIssuer;

  /* package */ ImpersonationTokenMinterBridge(final ImpersonationTokenIssuer tokenIssuer) {
    this.tokenIssuer = tokenIssuer;
  }

  @Override
  public MintedImpersonationToken mint(
      final AccountId accountId,
      final OrganizationId organizationId,
      final String clientId,
      final List<String> scopes,
      final AuditActor actor,
      final String baseUrl) {
    try {
      final ImpersonationTokenIssuer.ImpersonationToken token =
          tokenIssuer.mint(accountId, organizationId, clientId, scopes, actor, baseUrl);
      return new MintedImpersonationToken(
          token.accessToken(), "Bearer", token.expiresAt(), token.scopes());
    } catch (
        final com.clavaris.app.infrastructure.adapter.in.web.ImpersonationClientNotFoundException
            _) {
      throw new ImpersonationClientNotFoundException(clientId);
    } catch (
        final com.clavaris.app.infrastructure.adapter.in.web.ImpersonationScopeNotAllowedException
            _) {
      throw new ImpersonationScopeNotAllowedException(clientId);
    }
  }
}

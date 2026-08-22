package com.clavaris.identity.application.usecase.issuerefreshtoken;

/**
 * Inbound port — opens a brand-new {@link com.clavaris.identity.domain.model.Session} and issues
 * its first {@link com.clavaris.identity.domain.model.RefreshToken}. Called once per interactive
 * grant that issues a refresh token (the initial Authorization Code exchange), never on rotation —
 * {@code application.usecase.rotaterefreshtoken.RotateRefreshTokenUseCase} is that path.
 */
@FunctionalInterface
public interface IssueRefreshTokenUseCase {

  IssueRefreshTokenResult handle(IssueRefreshTokenCommand command);
}

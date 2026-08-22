package com.clavaris.identity.application.usecase.issuerefreshtoken;

import java.time.Instant;
import java.util.UUID;

/** {@code rawToken} is the only place the bearer value ever exists outside the caller's memory. */
public record IssueRefreshTokenResult(UUID sessionId, String rawToken, Instant expiresAt) {}

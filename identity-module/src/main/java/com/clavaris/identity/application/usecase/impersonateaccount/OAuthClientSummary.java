package com.clavaris.identity.application.usecase.impersonateaccount;

import java.util.List;
import java.util.UUID;

/**
 * SDE-III review, 2026-09-19 — just enough of client-registry-module's {@code OAuthClient} for the
 * Impersonate modal's client picker (id + {@code clientId} label + {@code allowedScopes}) — {@code
 * OAuthClient} itself has no separate display "name" field, so {@code clientId} is the identifying
 * label.
 */
@SuppressWarnings("PMD.ShortVariable")
public record OAuthClientSummary(UUID id, String clientId, List<String> allowedScopes) {}

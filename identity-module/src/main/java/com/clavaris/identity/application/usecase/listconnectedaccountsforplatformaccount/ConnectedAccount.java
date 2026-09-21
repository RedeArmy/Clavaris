package com.clavaris.identity.application.usecase.listconnectedaccountsforplatformaccount;

import com.clavaris.identity.domain.model.SocialProvider;
import java.time.Instant;

/**
 * ADR-0026: the self-service "Connected accounts" row shape — deliberately never exposes {@code
 * providerUserId} (the provider's own opaque subject identifier has no display value and is never
 * PII-safe-by-default to show back to the account holder as-is).
 */
public record ConnectedAccount(SocialProvider provider, Instant linkedAt) {}

package com.clavaris.identity.application.usecase.getloginactivityforaccount;

import java.time.LocalDate;

/**
 * One calendar day with at least one login, and how many — the shape {@code
 * GetLoginActivityForAccountUseCase} returns, the "View Profile" activity heatmap renders directly.
 * A day with zero logins is simply absent from the result, never a zero-count entry.
 */
public record LoginActivityDay(LocalDate date, long count) {}

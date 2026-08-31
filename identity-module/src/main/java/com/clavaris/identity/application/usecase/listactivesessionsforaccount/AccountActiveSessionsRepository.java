package com.clavaris.identity.application.usecase.listactivesessionsforaccount;

import com.clavaris.identity.domain.model.AccountId;
import java.util.List;
import java.util.Optional;

/**
 * Outbound port — implemented in {@code app} against Spring Session's own {@code
 * FindByIndexNameSessionRepository}/{@code SessionRegistry} (identity-module deliberately never
 * depends on {@code spring-security-config}, same rationale as every other port in this package
 * hierarchy). One port, reused by both this package's own query and {@code revokeaccountsession}'s
 * command — same "one port, several use cases" precedent as {@code AccountRepository}.
 *
 * <p>{@link #revoke} is deliberately unconditional (no ownership check) — {@code
 * revokeaccountsession.RevokeAccountSessionService} is where that business rule lives, via {@link
 * #findByAccountIdAndSessionId} first; this port itself stays a dumb Spring-Session adapter.
 */
public interface AccountActiveSessionsRepository {

  List<ActiveAccountSession> findAllByAccountId(AccountId accountId);

  Optional<ActiveAccountSession> findByAccountIdAndSessionId(AccountId accountId, String sessionId);

  void revoke(String sessionId);
}

package com.clavaris.identity.application.usecase.registerplatformaccount;

import com.clavaris.identity.domain.model.Email;
import com.clavaris.identity.domain.model.PlatformAccount;
import com.clavaris.identity.domain.model.PlatformAccountId;
import java.util.Optional;

/**
 * Outbound port — implemented by {@code
 * infrastructure/adapter/out/persistence/JpaPlatformAccountRepository}. Parked under {@code
 * registerplatformaccount} because that's this concept's first use case, same precedent as {@code
 * registeraccount.AccountRepository}.
 */
public interface PlatformAccountRepository {

  /** Global uniqueness — no Organization to scope by, unlike {@code AccountRepository}'s own. */
  boolean existsByEmail(Email email);

  Optional<PlatformAccount> findByEmail(Email email);

  Optional<PlatformAccount> findById(PlatformAccountId platformAccountId);

  void save(PlatformAccount account);
}

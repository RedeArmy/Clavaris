package com.clavaris.identity.application.usecase.recordaccountlogindevice;

import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.KnownDevice;
import java.util.Optional;

/**
 * Outbound port — implemented in {@code
 * infrastructure/adapter/out/persistence/JpaKnownDeviceRepository}.
 */
public interface KnownDeviceRepository {

  Optional<KnownDevice> findByAccountIdAndUserAgent(AccountId accountId, String userAgent);

  void save(KnownDevice device);
}

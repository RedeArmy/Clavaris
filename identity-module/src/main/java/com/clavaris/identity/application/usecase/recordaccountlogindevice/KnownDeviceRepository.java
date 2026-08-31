package com.clavaris.identity.application.usecase.recordaccountlogindevice;

import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.KnownDevice;
import java.util.Optional;

/**
 * Outbound port — implemented in {@code
 * infrastructure/adapter/out/persistence/JpaKnownDeviceRepository}.
 */
public interface KnownDeviceRepository {

  /**
   * TD-SEC-033: the real match key, since {@code deviceTokenHash} (not {@code userAgent}) is what
   * an attacker can't forge — see {@code KnownDevice}'s own Javadoc.
   */
  Optional<KnownDevice> findByAccountIdAndDeviceTokenHash(
      AccountId accountId, String deviceTokenHash);

  /**
   * @throws org.springframework.dao.DataIntegrityViolationException synchronously, on a {@code
   *     UNIQUE(account_id, device_token_hash)} violation — implementations must flush immediately,
   *     not defer to the surrounding transaction's own commit, so {@link
   *     RecordAccountLoginDeviceService}'s own defensive try/catch around this call actually
   *     catches it.
   */
  void save(KnownDevice device);
}

package com.clavaris.identity.infrastructure.adapter.out.persistence;

import com.clavaris.identity.application.usecase.recordaccountlogindevice.KnownDeviceRepository;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.KnownDevice;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * Implements the outbound port; maps between {@code domain.model.KnownDevice} (framework-free) and
 * {@link KnownDeviceEntity}.
 */
@Repository
class JpaKnownDeviceRepository implements KnownDeviceRepository {

  private final SpringDataKnownDeviceJpaRepository devices;

  /* package */ JpaKnownDeviceRepository(final SpringDataKnownDeviceJpaRepository devices) {
    this.devices = devices;
  }

  @Override
  public Optional<KnownDevice> findByAccountIdAndDeviceTokenHash(
      final AccountId accountId, final String deviceTokenHash) {
    return devices
        .findByAccountIdAndDeviceTokenHash(accountId.value(), deviceTokenHash)
        .map(this::toDomain);
  }

  @Override
  public void save(final KnownDevice device) {
    // saveAndFlush, not save: the unique constraint on known_devices.(account_id,
    // device_token_hash) must throw synchronously, right here, so RecordAccountLoginDeviceService's
    // own try/catch around this call actually catches it — same "plain save() only stages the
    // insert in the persistence context, deferring execution past the caller's own try/catch"
    // rationale JpaAccountRepository's own identical saveAndFlush already documents.
    devices.saveAndFlush(
        new KnownDeviceEntity(
            device.id(),
            device.accountId().value(),
            device.userAgent(),
            device.deviceTokenHash(),
            device.firstSeenAt(),
            device.lastSeenAt()));
  }

  private KnownDevice toDomain(final KnownDeviceEntity entity) {
    return KnownDevice.reconstitute(
        entity.getId(),
        new AccountId(entity.getAccountId()),
        entity.getUserAgent(),
        entity.getDeviceTokenHash(),
        entity.getFirstSeenAt(),
        entity.getLastSeenAt());
  }
}

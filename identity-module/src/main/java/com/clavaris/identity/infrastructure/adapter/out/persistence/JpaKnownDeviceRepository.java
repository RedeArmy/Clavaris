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
  public Optional<KnownDevice> findByAccountIdAndUserAgent(
      final AccountId accountId, final String userAgent) {
    return devices.findByAccountIdAndUserAgent(accountId.value(), userAgent).map(this::toDomain);
  }

  @Override
  public void save(final KnownDevice device) {
    devices.save(
        new KnownDeviceEntity(
            device.id(),
            device.accountId().value(),
            device.userAgent(),
            device.firstSeenAt(),
            device.lastSeenAt()));
  }

  private KnownDevice toDomain(final KnownDeviceEntity entity) {
    return KnownDevice.reconstitute(
        entity.getId(),
        new AccountId(entity.getAccountId()),
        entity.getUserAgent(),
        entity.getFirstSeenAt(),
        entity.getLastSeenAt());
  }
}

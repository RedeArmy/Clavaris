package com.clavaris.identity.infrastructure.adapter.out.persistence;

import com.clavaris.identity.application.usecase.recordplatformaccountlogindevice.PlatformKnownDeviceRepository;
import com.clavaris.identity.domain.model.PlatformAccountId;
import com.clavaris.identity.domain.model.PlatformKnownDevice;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * Implements the outbound port; maps between {@code domain.model.PlatformKnownDevice} (framework-
 * free) and {@link PlatformKnownDeviceEntity}.
 */
@Repository
class JpaPlatformKnownDeviceRepository implements PlatformKnownDeviceRepository {

  private final SpringDataPlatformKnownDeviceJpaRepository devices;

  /* package */ JpaPlatformKnownDeviceRepository(
      final SpringDataPlatformKnownDeviceJpaRepository devices) {
    this.devices = devices;
  }

  @Override
  public Optional<PlatformKnownDevice> findByPlatformAccountIdAndDeviceTokenHash(
      final PlatformAccountId platformAccountId, final String deviceTokenHash) {
    return devices
        .findByPlatformAccountIdAndDeviceTokenHash(platformAccountId.value(), deviceTokenHash)
        .map(this::toDomain);
  }

  @Override
  public boolean existsByPlatformAccountId(final PlatformAccountId platformAccountId) {
    return devices.existsByPlatformAccountId(platformAccountId.value());
  }

  @Override
  public void save(final PlatformKnownDevice device) {
    // saveAndFlush, not save — same "must throw synchronously" rationale as
    // JpaKnownDeviceRepository's own identical save().
    devices.saveAndFlush(
        new PlatformKnownDeviceEntity(
            device.id(),
            device.platformAccountId().value(),
            device.userAgent(),
            device.deviceTokenHash(),
            device.firstSeenAt(),
            device.lastSeenAt()));
  }

  private PlatformKnownDevice toDomain(final PlatformKnownDeviceEntity entity) {
    return PlatformKnownDevice.reconstitute(
        entity.getId(),
        new PlatformAccountId(entity.getPlatformAccountId()),
        entity.getUserAgent(),
        entity.getDeviceTokenHash(),
        entity.getFirstSeenAt(),
        entity.getLastSeenAt());
  }
}

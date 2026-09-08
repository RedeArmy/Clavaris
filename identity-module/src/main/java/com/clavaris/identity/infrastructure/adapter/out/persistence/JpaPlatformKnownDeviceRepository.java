package com.clavaris.identity.infrastructure.adapter.out.persistence;

import com.clavaris.identity.application.usecase.recordplatformaccountlogindevice.PlatformKnownDeviceRepository;
import com.clavaris.identity.domain.model.PlatformAccountId;
import com.clavaris.identity.domain.model.PlatformKnownDevice;
import jakarta.persistence.EntityManager;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implements the outbound port; maps between {@code domain.model.PlatformKnownDevice} (framework-
 * free) and {@link PlatformKnownDeviceEntity}.
 *
 * <p>TD-PERF-019: {@code insert} calls {@link EntityManager#persist} directly — see {@code
 * PlatformKnownDeviceRepository#insert}'s own Javadoc.
 */
@Repository
class JpaPlatformKnownDeviceRepository implements PlatformKnownDeviceRepository {

  private final SpringDataPlatformKnownDeviceJpaRepository devices;
  private final EntityManager entityManager;

  /* package */ JpaPlatformKnownDeviceRepository(
      final SpringDataPlatformKnownDeviceJpaRepository devices, final EntityManager entityManager) {
    this.devices = devices;
    this.entityManager = entityManager;
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
    devices.saveAndFlush(toEntity(device));
  }

  @Override
  @Transactional
  public void insert(final PlatformKnownDevice device) {
    // persist + explicit flush — same synchronous-throw contract save()'s saveAndFlush documents.
    entityManager.persist(toEntity(device));
    entityManager.flush();
  }

  private PlatformKnownDeviceEntity toEntity(final PlatformKnownDevice device) {
    return new PlatformKnownDeviceEntity(
        device.id(),
        device.platformAccountId().value(),
        device.userAgent(),
        device.deviceTokenHash(),
        device.firstSeenAt(),
        device.lastSeenAt());
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

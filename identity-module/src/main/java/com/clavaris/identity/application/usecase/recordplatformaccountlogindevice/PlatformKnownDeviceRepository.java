package com.clavaris.identity.application.usecase.recordplatformaccountlogindevice;

import com.clavaris.identity.domain.model.PlatformAccountId;
import com.clavaris.identity.domain.model.PlatformKnownDevice;
import java.util.Optional;

/**
 * Outbound port — implemented in {@code
 * infrastructure/adapter/out/persistence/JpaPlatformKnownDeviceRepository}. TD-FUT-026: platform-
 * tier mirror of {@code recordaccountlogindevice.KnownDeviceRepository}.
 */
public interface PlatformKnownDeviceRepository {

  Optional<PlatformKnownDevice> findByPlatformAccountIdAndDeviceTokenHash(
      PlatformAccountId platformAccountId, String deviceTokenHash);

  /** Same "first-ever device" distinction {@code KnownDeviceRepository.existsByAccountId} makes. */
  boolean existsByPlatformAccountId(PlatformAccountId platformAccountId);

  /**
   * @throws org.springframework.dao.DataIntegrityViolationException synchronously — same
   *     "implementations must flush immediately" contract as {@code KnownDeviceRepository.save}.
   */
  void save(PlatformKnownDevice device);
}

package com.clavaris.identity.application.usecase.activateplatformsigningkey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.clavaris.identity.domain.model.PlatformSigningKey;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ActivatePlatformSigningKeyServiceTest {

  @Test
  void activatesANewKeyWhenNoneWasActiveBefore() {
    PlatformSigningKeyRepository repository = mock(PlatformSigningKeyRepository.class);
    when(repository.findActive()).thenReturn(Optional.empty());
    ActivatePlatformSigningKeyService service = new ActivatePlatformSigningKeyService(repository);

    PlatformSigningKey activated = service.handle("new-kid", "RS256");

    assertThat(activated.kid()).isEqualTo("new-kid");
    assertThat(activated.retiredAt()).isEmpty();
    verify(repository, times(1)).save(any());
  }

  @Test
  void retiresThePreviouslyActiveKeyBeforeActivatingTheNewOne() {
    // The old key's real material is gone the moment the previous process exited (this slice
    // doesn't persist it) — leaving its row marked active would misrepresent what the running
    // system can still actually verify against.
    PlatformSigningKeyRepository repository = mock(PlatformSigningKeyRepository.class);
    PlatformSigningKey previouslyActive = PlatformSigningKey.activate("old-kid", "RS256");
    when(repository.findActive()).thenReturn(Optional.of(previouslyActive));
    ActivatePlatformSigningKeyService service = new ActivatePlatformSigningKeyService(repository);

    service.handle("new-kid", "RS256");

    assertThat(previouslyActive.retiredAt()).isPresent();
    verify(repository, times(2)).save(any()); // once for the retired old key, once for the new one
  }
}

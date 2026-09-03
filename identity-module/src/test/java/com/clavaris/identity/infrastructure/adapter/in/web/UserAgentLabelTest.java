package com.clavaris.identity.infrastructure.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** TD-FUT-024: real, current User-Agent strings from each browser/OS this class claims to label. */
class UserAgentLabelTest {

  @Test
  void labelsChromeOnWindows() {
    String userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
            + "Chrome/128.0.0.0 Safari/537.36";

    assertThat(UserAgentLabel.friendly(userAgent)).isEqualTo("Chrome on Windows");
  }

  @Test
  void labelsChromeOnMacOs() {
    String userAgent =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) "
            + "Chrome/128.0.0.0 Safari/537.36";

    assertThat(UserAgentLabel.friendly(userAgent)).isEqualTo("Chrome on macOS");
  }

  @Test
  void labelsSafariOnMacOsNotChrome() {
    String userAgent =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) "
            + "Version/17.5 Safari/605.1.15";

    assertThat(UserAgentLabel.friendly(userAgent)).isEqualTo("Safari on macOS");
  }

  @Test
  void labelsFirefoxOnLinux() {
    String userAgent = "Mozilla/5.0 (X11; Linux x86_64; rv:128.0) Gecko/20100101 Firefox/128.0";

    assertThat(UserAgentLabel.friendly(userAgent)).isEqualTo("Firefox on Linux");
  }

  @Test
  void labelsEdgeOnWindowsNotChrome() {
    String userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
            + "Chrome/128.0.0.0 Safari/537.36 Edg/128.0.0.0";

    assertThat(UserAgentLabel.friendly(userAgent)).isEqualTo("Edge on Windows");
  }

  @Test
  void labelsSafariOnIosNotMacOs() {
    // An iOS User-Agent's own platform token literally contains "like Mac OS X" — the exact case
    // this class's own Javadoc names as the reason iOS must be checked before macOS.
    String userAgent =
        "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like "
            + "Gecko) Version/17.5 Mobile/15E148 Safari/604.1";

    assertThat(UserAgentLabel.friendly(userAgent)).isEqualTo("Safari on iOS");
  }

  @Test
  void labelsChromeOnAndroidNotLinux() {
    String userAgent =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) "
            + "Chrome/128.0.0.0 Mobile Safari/537.36";

    assertThat(UserAgentLabel.friendly(userAgent)).isEqualTo("Chrome on Android");
  }

  @Test
  void fallsBackToTheRawStringWhenNeitherBrowserNorOsIsRecognized() {
    String userAgent = "SomeCustomBot/1.0 (+https://example.com/bot)";

    assertThat(UserAgentLabel.friendly(userAgent)).isEqualTo(userAgent);
  }

  @Test
  void returnsUnknownDeviceForNull() {
    assertThat(UserAgentLabel.friendly(null)).isEqualTo("Unknown device");
  }

  @Test
  void returnsUnknownDeviceForBlank() {
    assertThat(UserAgentLabel.friendly("   ")).isEqualTo("Unknown device");
  }
}

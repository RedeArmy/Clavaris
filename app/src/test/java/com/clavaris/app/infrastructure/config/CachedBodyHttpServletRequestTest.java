package com.clavaris.app.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.util.StreamUtils;

/**
 * Dedicated coverage for the one property {@link IdempotencyKeyFilterTest} doesn't already prove
 * end to end: that the body can genuinely be read twice, in full, from two independent {@link
 * #getInputStream}/{@link #getReader} calls — the entire reason this wrapper exists (see its own
 * Javadoc).
 */
class CachedBodyHttpServletRequestTest {

  @Test
  void bodyBytesReturnsTheExactUnderlyingRequestBody() throws Exception {
    MockHttpServletRequest underlying = new MockHttpServletRequest("POST", "/api/v1/admin/x");
    underlying.setContent("{\"name\":\"Acme\"}".getBytes(StandardCharsets.UTF_8));

    CachedBodyHttpServletRequest wrapped = new CachedBodyHttpServletRequest(underlying);

    assertThat(wrapped.bodyBytes())
        .isEqualTo("{\"name\":\"Acme\"}".getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void getInputStreamCanBeReadInFullMoreThanOnce() throws Exception {
    MockHttpServletRequest underlying = new MockHttpServletRequest("POST", "/api/v1/admin/x");
    underlying.setContent("{\"name\":\"Acme\"}".getBytes(StandardCharsets.UTF_8));
    CachedBodyHttpServletRequest wrapped = new CachedBodyHttpServletRequest(underlying);

    byte[] firstRead = StreamUtils.copyToByteArray(wrapped.getInputStream());
    byte[] secondRead = StreamUtils.copyToByteArray(wrapped.getInputStream());

    assertThat(firstRead).isEqualTo("{\"name\":\"Acme\"}".getBytes(StandardCharsets.UTF_8));
    assertThat(secondRead)
        .as("a second, independent read must see the exact same content, not an exhausted stream")
        .isEqualTo(firstRead);
  }

  @Test
  void getReaderDecodesTheBodyAsUtf8() throws Exception {
    MockHttpServletRequest underlying = new MockHttpServletRequest("POST", "/api/v1/admin/x");
    underlying.setContent("{\"name\":\"Ünïcödé\"}".getBytes(StandardCharsets.UTF_8));
    CachedBodyHttpServletRequest wrapped = new CachedBodyHttpServletRequest(underlying);

    String content = wrapped.getReader().lines().reduce("", (a, b) -> a + b);

    assertThat(content).isEqualTo("{\"name\":\"Ünïcödé\"}");
  }

  @Test
  void anEmptyBodyIsHandledCleanly() throws Exception {
    MockHttpServletRequest underlying = new MockHttpServletRequest("POST", "/api/v1/admin/x");
    underlying.setContent(new byte[0]);

    CachedBodyHttpServletRequest wrapped = new CachedBodyHttpServletRequest(underlying);

    assertThat(wrapped.bodyBytes()).isEmpty();
    assertThat(wrapped.getInputStream().isFinished())
        .as("an empty body has nothing left to read from the very first check")
        .isTrue();
    assertThat(wrapped.getInputStream().read()).isEqualTo(-1);
  }

  @Test
  void theInputStreamReportsUnfinishedUntilFullyConsumedThenFinished() throws Exception {
    MockHttpServletRequest underlying = new MockHttpServletRequest("POST", "/api/v1/admin/x");
    underlying.setContent("ab".getBytes(StandardCharsets.UTF_8));
    CachedBodyHttpServletRequest wrapped = new CachedBodyHttpServletRequest(underlying);

    jakarta.servlet.ServletInputStream stream = wrapped.getInputStream();

    assertThat(stream.isFinished()).isFalse();
    assertThat(stream.isReady())
        .as("always ready — an in-memory buffer, never a real socket")
        .isTrue();
    assertThat(stream.read()).isEqualTo('a');
    assertThat(stream.isFinished()).isFalse();
    assertThat(stream.read()).isEqualTo('b');
    assertThat(stream.isFinished()).as("every byte consumed").isTrue();
    assertThat(stream.read()).isEqualTo(-1);
  }

  @Test
  void setReadListenerIsANoOpThatNeverThrows() throws Exception {
    MockHttpServletRequest underlying = new MockHttpServletRequest("POST", "/api/v1/admin/x");
    underlying.setContent("{}".getBytes(StandardCharsets.UTF_8));
    CachedBodyHttpServletRequest wrapped = new CachedBodyHttpServletRequest(underlying);

    // Never actually invoked in production (see this stream's own Javadoc) — this only proves it
    // doesn't blow up if something ever does call it, same defensive-coverage bar the rest of this
    // class's small, trivial methods get.
    org.assertj.core.api.Assertions.assertThatCode(
            () -> wrapped.getInputStream().setReadListener(null))
        .doesNotThrowAnyException();
  }
}

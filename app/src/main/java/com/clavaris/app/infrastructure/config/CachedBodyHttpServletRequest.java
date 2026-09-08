package com.clavaris.app.infrastructure.config;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.springframework.util.StreamUtils;

/**
 * {@link IdempotencyKeyFilter} needs the raw request body twice — once, itself, to hash it before
 * deciding whether to let the request through at all; once more, for whatever the real controller
 * downstream reads. A plain {@link HttpServletRequest}'s own input stream can only ever be consumed
 * once. Spring's {@code ContentCachingRequestWrapper} does not solve this cleanly for a filter that
 * itself must read first — its cache only fills as something *downstream* reads through it, so a
 * filter that reads the stream itself before the chain runs would leave nothing for the real
 * controller to read afterward. This wrapper instead reads the entire body into memory once, in its
 * own constructor, and serves that same buffered copy to every subsequent {@link #getInputStream}/
 * {@link #getReader} call — including the real controller's own, later in the chain.
 *
 * <p>Bounded by design, not a general-purpose request-buffering mechanism: {@link
 * IdempotencyKeyFilter} only ever wraps a request that already matched {@code /api/v1/admin/**}, an
 * authenticated, low-volume, JSON management API — never a public, high-throughput, or
 * file-upload-shaped endpoint where buffering the full body in memory would be a real concern.
 */
class CachedBodyHttpServletRequest extends HttpServletRequestWrapper {

  private final byte[] cachedBody;

  /* package */ CachedBodyHttpServletRequest(final HttpServletRequest request) throws IOException {
    super(request);
    this.cachedBody = StreamUtils.copyToByteArray(request.getInputStream());
  }

  // PMD.MethodReturnsInternalArray: deliberate, not an oversight — this is a package-private
  // helper read exactly once per request, by IdempotencyKeyFilter itself, purely to compute a
  // digest; a defensive copy here would double this class's own memory cost for a value nothing
  // ever mutates after construction.
  @SuppressWarnings("PMD.MethodReturnsInternalArray")
  /* package */ byte[] bodyBytes() {
    return cachedBody;
  }

  @Override
  public ServletInputStream getInputStream() {
    return new CachedBodyServletInputStream(cachedBody);
  }

  @Override
  public BufferedReader getReader() {
    return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
  }

  /** A trivial, always-ready, non-async {@link ServletInputStream} over an in-memory byte array. */
  private static final class CachedBodyServletInputStream extends ServletInputStream {

    private final ByteArrayInputStream delegate;

    private CachedBodyServletInputStream(final byte[] body) {
      super();
      this.delegate = new ByteArrayInputStream(body);
    }

    @Override
    public boolean isFinished() {
      return delegate.available() == 0;
    }

    @Override
    public boolean isReady() {
      return true;
    }

    @Override
    public void setReadListener(final ReadListener readListener) {
      // Never actually invoked — this codebase's servlet container runs standard blocking I/O for
      // every request this filter wraps (the admin API is not a WebFlux/async-servlet endpoint),
      // same "no real async read listener ever registers here" assumption Spring's own
      // ContentCachingRequestWrapper makes for this identical method.
    }

    @Override
    public int read() {
      return delegate.read();
    }
  }
}

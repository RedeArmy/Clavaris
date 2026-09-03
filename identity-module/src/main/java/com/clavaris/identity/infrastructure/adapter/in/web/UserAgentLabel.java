package com.clavaris.identity.infrastructure.adapter.in.web;

import java.util.regex.Pattern;

/**
 * TD-FUT-024 (closed 2026-09-02): turns a raw {@code User-Agent} header into a short, human-
 * readable label ("Chrome on macOS") for the sessions/devices page (BR-ID-13). Public, not
 * package-private: TD-FUT-026's own platform-tier mirror of this page reuses this exact class
 * rather than duplicating the parsing rules a second time.
 *
 * <p>Deliberately bounded, not a general-purpose UA parser — same "no new dependency without real
 * justification" precedent {@code ResendMailSender}'s own Javadoc already documents for a
 * comparable call: a reliable general UA-parsing library is a materially bigger dependency than
 * this single page needs, and the raw string was already genuinely usable on its own (this class
 * only ever improves on it, never replaces it — see {@link #friendly}'s own fallback). Recognizes
 * only the small, closed set of desktop/mobile browser and OS tokens actually worth labeling for
 * this UI; anything else falls through to the raw string, never a hidden/wrong guess.
 *
 * <p>Order matters in both private pattern lists below — several real {@code User-Agent} strings
 * contain more than one matching token (every Chromium- or WebKit-derived browser's own UA also
 * contains the literal substring {@code "Safari/"}; an iOS device's own platform token literally
 * contains the substring {@code "like Mac OS X"}), so the more specific/derived value must be
 * checked before the more generic one it's built on.
 *
 * <p>PMD.OnlyOneReturn: every early return below (in {@link #friendly}, {@link #browser}, {@link
 * #operatingSystem}) is one independent, equally valid "this pattern matched" exit out of a chain
 * of mutually-exclusive checks — same rationale as every other early-return chain in this codebase.
 * PMD.ShortVariable: {@code os} names exactly what it is, in a method whose whole body is about
 * naming a browser and an OS — a longer name would only add noise here.
 */
@SuppressWarnings({"PMD.OnlyOneReturn", "PMD.ShortVariable"})
public final class UserAgentLabel {

  private static final Pattern EDGE = Pattern.compile("Edg(e|A|iOS)?/");
  private static final Pattern OPERA = Pattern.compile("OPR/|Opera/");
  private static final Pattern CHROME = Pattern.compile("Chrome/|CriOS/");
  private static final Pattern FIREFOX = Pattern.compile("Firefox/|FxiOS/");
  private static final Pattern INTERNET_EXPLORER = Pattern.compile("MSIE |Trident/");
  // Checked last of the browsers: every browser pattern above also matches this one in its own
  // real User-Agent string (they're all WebKit/Blink-derived), so a genuine Safari match only
  // means anything once none of the more specific browsers above matched first.
  private static final Pattern SAFARI = Pattern.compile("Safari/");

  // Checked before Windows/macOS/Linux below, for the same "more specific first" reason.
  private static final Pattern IOS = Pattern.compile("iPhone|iPad|iPod");
  private static final Pattern ANDROID = Pattern.compile("Android");
  private static final Pattern WINDOWS = Pattern.compile("Windows NT");
  private static final Pattern MAC_OS = Pattern.compile("Mac OS X");
  private static final Pattern LINUX = Pattern.compile("Linux");

  private UserAgentLabel() {}

  /**
   * @param userAgent the raw {@code User-Agent} header value, or {@code null}/blank for a session
   *     that carries none (a pre-BR-ID-13 session, or a client that sent no header at all)
   * @return a short "{Browser} on {OS}" label when both are recognized; just the browser or just
   *     the OS when only one is; the raw {@code userAgent} unchanged when neither is recognized (a
   *     real, if unlabeled, value is always better than a wrong or hidden guess); {@code "Unknown
   *     device"} only when there was no raw string to fall back to at all
   */
  public static String friendly(final String userAgent) {
    if (userAgent == null || userAgent.isBlank()) {
      return "Unknown device";
    }
    final String browser = browser(userAgent);
    final String os = operatingSystem(userAgent);
    if (browser == null && os == null) {
      return userAgent;
    }
    if (os == null) {
      return browser;
    }
    if (browser == null) {
      return os;
    }
    return browser + " on " + os;
  }

  private static String browser(final String userAgent) {
    if (EDGE.matcher(userAgent).find()) {
      return "Edge";
    }
    if (OPERA.matcher(userAgent).find()) {
      return "Opera";
    }
    if (CHROME.matcher(userAgent).find()) {
      return "Chrome";
    }
    if (FIREFOX.matcher(userAgent).find()) {
      return "Firefox";
    }
    if (INTERNET_EXPLORER.matcher(userAgent).find()) {
      return "Internet Explorer";
    }
    if (SAFARI.matcher(userAgent).find()) {
      return "Safari";
    }
    return null;
  }

  private static String operatingSystem(final String userAgent) {
    if (IOS.matcher(userAgent).find()) {
      return "iOS";
    }
    if (ANDROID.matcher(userAgent).find()) {
      return "Android";
    }
    if (WINDOWS.matcher(userAgent).find()) {
      return "Windows";
    }
    if (MAC_OS.matcher(userAgent).find()) {
      return "macOS";
    }
    if (LINUX.matcher(userAgent).find()) {
      return "Linux";
    }
    return null;
  }
}

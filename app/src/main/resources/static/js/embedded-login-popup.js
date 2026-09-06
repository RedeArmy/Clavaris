/**
 * ADR-0009 §1: inside a consumer's own iframe modal (display=modal), a social-provider link must
 * never navigate the iframe itself — the provider's own consent screen refuses to render inside
 * an iframe regardless (every major provider sends its own frame-ancestors/X-Frame-Options), so a
 * plain in-frame click would just dead-end on a blank, provider-refused frame. Opening it as a
 * real top-level popup window instead lets the provider's consent screen render normally; when
 * that flow finishes, SocialLoginAuthenticationSuccessHandler's own final redirect lands the
 * popup on the consuming application's own registered redirect_uri.
 *
 * <p>This script only ever changes behavior when the current page's own <body data-modal="true">
 * is set (LoginController only sets it when display=modal was actually requested) — a normal,
 * non-embedded hosted-login visit is completely unaffected, including its own address bar
 * navigating normally to the social provider and back.
 *
 * <p><b>Consumer-side requirement, not built or hosted by Clavaris:</b> the popup's own final
 * redirect_uri page must itself relay the outcome back to the parent (opening) window via
 * `window.opener.postMessage(...)`, then close itself — Clavaris cannot host this page, since it
 * lives on the consuming application's own origin (its registered redirect_uri), not Clavaris's.
 * A minimal reference implementation for that page:
 *
 * <pre>{@code
 * <script>
 *   if (window.opener) {
 *     window.opener.postMessage(
 *       { source: "clavaris-embedded-login", url: window.location.href },
 *       "https://your-app.example.com" // exact origin, never "*"
 *     );
 *     window.close();
 *   }
 * </script>
 * }</pre>
 *
 * The parent page (the one that opened the iframe in the first place) listens for that message —
 * `window.addEventListener("message", ...)`, checking `event.origin` against Clavaris's own
 * custom domain before trusting `event.data` — and then completes the flow on its own (closing
 * the iframe modal, exchanging the code from `event.data.url`, etc.).
 */
(function () {
  "use strict";

  document.addEventListener("DOMContentLoaded", function () {
    if (document.body.getAttribute("data-modal") !== "true") {
      return;
    }

    var socialLinks = document.querySelectorAll("a[data-social-login]");
    for (var i = 0; i < socialLinks.length; i += 1) {
      socialLinks[i].addEventListener("click", function (event) {
        event.preventDefault();
        // Deliberately no "noopener" in the features string below — the popup's own final
        // redirect_uri page needs window.opener to still be set so it can post the outcome back.
        window.open(event.currentTarget.href, "_blank", "width=480,height=640");
      });
    }
  });
})();

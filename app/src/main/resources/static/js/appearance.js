(() => {
  "use strict";

  // Clerk "appearance variables" parity: the consuming application tells the hosted sign-in /
  // consent / account pages what its own page background is (?appearance_bg=#rrggbb on the login or
  // authorize URL, or detected by its embedded-login script), and these pages adopt it so they read
  // as part of that application. Nothing is configured in Clavaris for this: it is a per-request
  // value, so an application can match a theme change without touching the dashboard.
  //
  // Kept in sessionStorage so the pages that follow in the same tab (sign-up, forgot password, the
  // embedded iframe's next screen) keep the colour without every link and form carrying the query.
  // Only a plain #rrggbb ever reaches a style, so the value cannot inject CSS. setProperty on the
  // CSSOM is not an inline style attribute, so the pages' style-src 'self' policy allows it.
  const PARAM = "appearance_bg";
  const STORAGE_KEY = "clavaris.appearance.bg";
  const HEX = /^#[0-9a-f]{6}$/i;

  const fromUrl = () => {
    try {
      return new URLSearchParams(window.location.search).get(PARAM);
    } catch {
      return null;
    }
  };

  const read = () => {
    try {
      return window.sessionStorage.getItem(STORAGE_KEY);
    } catch {
      return null;
    }
  };

  const write = (value) => {
    try {
      window.sessionStorage.setItem(STORAGE_KEY, value);
    } catch {
      // Storage can be blocked (private mode, partitioned iframe): the colour then applies to this page only.
    }
  };

  const requested = fromUrl();
  if (requested !== null && HEX.test(requested)) {
    write(requested.toLowerCase());
  }
  const colour = HEX.test(requested ?? "") ? requested.toLowerCase() : read();
  if (colour !== null && HEX.test(colour)) {
    document.documentElement.style.setProperty("--clavaris-bg", colour);
    // The palette (text, surfaces) follows the colour's brightness, not the OS colour scheme, so a
    // light application background never gets the dark palette's near-white text and vice versa.
    const [red, green, blue] = [1, 3, 5].map((i) => Number.parseInt(colour.slice(i, i + 2), 16));
    const light = (0.299 * red + 0.587 * green + 0.114 * blue) / 255 > 0.55;
    document.documentElement.dataset.appearance = light ? "light" : "dark";
  }
})();

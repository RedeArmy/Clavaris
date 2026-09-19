(() => {
  "use strict";

  // SDE-III review, 2026-09-19 — Clerk dashboard "Users" tab 3-dot row menu
  // (.clavaris-menu, a native <details>/<summary> disclosure, same pattern as the sidebar's own
  // .clavaris-profile account menu). Its panel is position: fixed rather than the .clavaris-profile
  // panel's position: absolute — the row this trigger lives in sits inside a
  // .clavaris-table-wrapper, which needs overflow: hidden for its own rounded corners, and that
  // would otherwise clip an open dropdown extending past the table. Positioned here, from the
  // trigger's own bounding rect, on every native "toggle" event.
  document.addEventListener(
    "toggle",
    (event) => {
      const details = event.target;
      if (!(details instanceof HTMLDetailsElement) || !details.matches(".clavaris-menu")) {
        return;
      }
      const panel = details.querySelector(".clavaris-menu__panel");
      const trigger = details.querySelector(".clavaris-menu__trigger");
      if (!details.open || !panel || !trigger) {
        return;
      }
      const rect = trigger.getBoundingClientRect();
      panel.style.top = `${rect.bottom + 6}px`;
      panel.style.left = `${Math.max(8, rect.right - panel.offsetWidth)}px`;
    },
    true,
  );

  // Native <details> has no built-in "close on outside click" — closing only the ones this click
  // didn't originate inside keeps every other open menu (there should only ever be one, but this
  // stays correct even if that changes) and any unrelated <details> elsewhere on the page alone.
  document.addEventListener("click", (event) => {
    document.querySelectorAll(".clavaris-menu[open]").forEach((details) => {
      if (!details.contains(event.target)) {
        details.open = false;
      }
    });
  });
})();

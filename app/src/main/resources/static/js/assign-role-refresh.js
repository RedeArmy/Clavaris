(() => {
  "use strict";

  // ADR-0029: htmx's own HX-Trigger response header fires this as a plain DOM event on
  // document.body once the swap completes — organization-module's own
  // PlatformAccountWorkspaceRoleController sets it on a successful save, without either side
  // knowing the other module's Java types (both only agree on this one event name). A full reload
  // is deliberate, not a shortcut: the Role column lives on this page (identity-module) but its
  // data comes from organization-module's own tables, and re-fetching just this page's own
  // fragment would still need a second round trip regardless — simplest correct fix for an action
  // this infrequent.
  document.body.addEventListener("workspace-role-assigned", () => {
    document.querySelector("dialog[open]")?.close();
    window.location.reload();
  });
})();

(() => {
  "use strict";

  // Live UX request, 2026-09-27 (real bug found: this file was referenced by
  // assign-role-form.html's own header comment but never actually existed anywhere in the repo —
  // the Team filter it describes was a complete no-op). Filters #assignRoleNewRoleId's own
  // <option>s by the chosen #assignRoleTeamId's value, matching each option's own data-team-id —
  // same "grouped picker, no second round trip" convention scope-picker.js/event-type-picker.js
  // already establish for an identical parent/child shape. This fragment is only ever loaded via
  // an htmx swap (into the shared #assign-role-dialog-body, first on open, again on a Workspace
  // change or a validation-error redisplay) — never a plain page load — so initialization hooks
  // off htmx's own afterSwap lifecycle event, not DOMContentLoaded, which would only ever fire
  // once and never again for a dialog re-opened or reloaded later.

  const filterRoleOptionsByTeam = (teamSelect) => {
    const roleSelect = document.getElementById("assignRoleNewRoleId");
    if (!roleSelect) {
      return;
    }
    const selectedTeamId = teamSelect.value;
    let selectedOptionStillVisible = false;
    Array.from(roleSelect.options).forEach((option) => {
      const matches = option.dataset.teamId === selectedTeamId;
      option.hidden = !matches;
      option.disabled = !matches;
      if (matches && option.selected) {
        selectedOptionStillVisible = true;
      }
    });
    if (!selectedOptionStillVisible) {
      const firstVisible = Array.from(roleSelect.options).find((option) => !option.hidden);
      if (firstVisible) {
        roleSelect.value = firstVisible.value;
      }
    }
  };

  document.addEventListener("change", (event) => {
    if (event.target.id === "assignRoleTeamId") {
      filterRoleOptionsByTeam(event.target);
    }
  });

  // Applies the filter immediately whenever this fragment lands in the DOM — otherwise the Role
  // select would show every role unfiltered until the operator manually touches the Team select,
  // even though a real team is already pre-selected (th:selected, matching the account's own
  // current role).
  document.body.addEventListener("htmx:afterSwap", () => {
    const teamSelect = document.getElementById("assignRoleTeamId");
    if (teamSelect) {
      filterRoleOptionsByTeam(teamSelect);
    }
  });
})();

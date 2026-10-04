(() => {
  "use strict";

  // Live UX request, 2026-10-04 — the Personal information card's phone number. The country-code
  // <select> listed ~240 options as "🇬🇹 +502 Guatemala" in a 128px box, so the closed control read
  // "GT +502 Gua", the flag drew as two letters on Windows (which has no flag glyphs), and — because
  // every option starts with the emoji — typing a country name to jump to it never worked.
  //
  // Progressive enhancement over that <select> (data-phone-country-picker): the <select> stays in the
  // DOM as the one source of truth that is submitted, so the server contract (phoneCountryCode +
  // phoneNumberLocal) is untouched and, with no JS, the original control still works. This script
  // hides it and puts a compact "GT +502" button in its place, which opens a searchable list
  // (name, ISO code or dial code: "guat", "gt", "502" and "+1" all find what you mean). The ISO badge
  // is derived from the flag emoji itself, so it is identical on every OS instead of depending on the
  // platform's emoji font.
  //
  // Combobox pattern (WAI-ARIA APG, select-only with filtering): the search input owns focus and
  // points at the highlighted option through aria-activedescendant; Arrow keys, Home/End, Enter and
  // Escape behave as expected, and the result count is announced through a polite live region. No
  // inline styles or scripts (CSP: style-src 'self').
  const OPTION_PATTERN = /^(\S+)\s+(\+\d+)\s+(.+)$/u;
  const REGIONAL_INDICATOR_A = 0x1f1e6;
  const REGIONAL_INDICATOR_Z = 0x1f1ff;
  const PANEL_ROOM = 340;
  const PANEL_WIDTH = 340;
  const PANEL_MARGIN = 8;
  const PANEL_GAP = 6;
  const LIST_MIN = 120;
  const LIST_MAX = 264;
  const LIST_CHROME = 90;

  // "🇬🇹" is two regional-indicator code points; map each back to its letter ("GT").
  const isoFromFlag = (flag) =>
    Array.from(flag)
      .map((symbol) => symbol.codePointAt(0))
      .filter((code) => code >= REGIONAL_INDICATOR_A && code <= REGIONAL_INDICATOR_Z)
      .map((code) => String.fromCharCode(65 + code - REGIONAL_INDICATOR_A))
      .join("");

  const fold = (text) =>
    text
      .normalize("NFD")
      .replace(/\p{Diacritic}/gu, "")
      .toLowerCase();

  const element = (tag, className, text) => {
    const node = document.createElement(tag);
    if (className) {
      node.className = className;
    }
    if (text !== undefined) {
      node.textContent = text;
    }
    return node;
  };

  const chevron = () => {
    const svg = document.createElementNS("http://www.w3.org/2000/svg", "svg");
    svg.setAttribute("class", "clavaris-icon clavaris-phone-code__chevron");
    svg.setAttribute("viewBox", "0 0 24 24");
    svg.setAttribute("aria-hidden", "true");
    const path = document.createElementNS("http://www.w3.org/2000/svg", "path");
    path.setAttribute("d", "m6 9 6 6 6-6");
    svg.append(path);
    return svg;
  };

  const parseCountries = (select) =>
    Array.from(select.options)
      .map((option, index) => ({ option, index, match: OPTION_PATTERN.exec(option.text.trim()) }))
      .filter((entry) => entry.match && entry.option.value)
      .map(({ option, index, match }) => ({
        index,
        iso: isoFromFlag(match[1]),
        dial: match[2],
        name: match[3],
        search: fold(match[3] + " " + isoFromFlag(match[1]) + " " + match[2]),
        value: option.value,
      }));

  const rank = (country, query) => {
    const dialDigits = country.dial.replace("+", "");
    const typedDigits = query.replace("+", "");
    let score = 2;
    if (country.iso.toLowerCase() === query || country.dial === query || dialDigits === typedDigits) {
      score = 0;
    } else if (fold(country.name).startsWith(query) || country.dial.startsWith("+" + typedDigits)) {
      score = 1;
    }
    return score;
  };

  const enhance = (select) => {
    const countries = parseCountries(select);
    if (countries.length === 0 || select.dataset.enhanced) {
      return;
    }
    select.dataset.enhanced = "true";

    const baseId = select.id;
    const listId = baseId + "-list";
    const next = document.getElementById(select.dataset.phoneNext || "");
    const preview = document.getElementById(select.dataset.phonePreview || "");

    const trigger = element("button", "clavaris-phone-code");
    trigger.type = "button";
    trigger.id = baseId + "-trigger";
    trigger.setAttribute("aria-haspopup", "listbox");
    trigger.setAttribute("aria-expanded", "false");
    trigger.setAttribute("aria-controls", baseId + "-panel");
    const iso = element("span", "clavaris-phone-code__iso");
    const dial = element("span", "clavaris-phone-code__dial");
    trigger.append(iso, dial, chevron());

    const panel = element("div", "clavaris-phone-code__panel");
    panel.id = baseId + "-panel";
    panel.hidden = true;
    const search = element("input", "clavaris-phone-code__search");
    search.type = "search";
    search.placeholder = "Search country or code";
    search.autocomplete = "off";
    search.spellcheck = false;
    search.setAttribute("role", "combobox");
    search.setAttribute("aria-label", "Search country or dial code");
    search.setAttribute("aria-controls", listId);
    search.setAttribute("aria-expanded", "true");
    search.setAttribute("aria-autocomplete", "list");
    const list = element("ul", "clavaris-phone-code__list");
    list.id = listId;
    list.setAttribute("role", "listbox");
    list.setAttribute("aria-label", "Countries");
    const status = element("output", "clavaris-visually-hidden");
    status.setAttribute("aria-live", "polite");
    const empty = element("p", "clavaris-phone-code__empty", "No country matches that.");
    empty.hidden = true;
    panel.append(search, list, empty, status);

    const rows = countries.map((country) => {
      const row = element("li", "clavaris-phone-code__option");
      row.id = baseId + "-option-" + country.index;
      row.setAttribute("role", "option");
      row.append(
        element("span", "clavaris-phone-code__iso", country.iso),
        element("span", "clavaris-phone-code__name", country.name),
        element("span", "clavaris-phone-code__dial", country.dial),
      );
      row.dataset.index = String(country.index);
      list.append(row);
      return { row, country };
    });

    let visible = rows;
    let active = -1;

    const selectedCountry = () => countries.find((country) => country.index === select.selectedIndex);

    const renderTrigger = () => {
      const chosen = selectedCountry();
      iso.textContent = chosen ? chosen.iso : "";
      iso.hidden = !chosen;
      dial.textContent = chosen ? chosen.dial : "Code";
      trigger.classList.toggle("is-empty", !chosen);
      rows.forEach(({ row, country }) => row.setAttribute("aria-selected", String(country === chosen)));
      trigger.setAttribute(
        "aria-label",
        chosen
          ? "Country code, " + chosen.name + " " + chosen.dial + ". Change"
          : "Country code. Choose",
      );
    };

    const renderPreview = () => {
      if (!preview) {
        return;
      }
      const chosen = selectedCountry();
      const local = next && next.value.trim();
      preview.textContent = chosen && local ? "Will be saved as " + chosen.dial + " " + local : "";
    };

    const setActive = (position) => {
      active = position;
      rows.forEach(({ row }) => row.classList.remove("is-active"));
      const current = visible[position];
      if (current) {
        current.row.classList.add("is-active");
        search.setAttribute("aria-activedescendant", current.row.id);
        current.row.scrollIntoView({ block: "nearest" });
      } else {
        search.removeAttribute("aria-activedescendant");
      }
    };

    const filter = (raw) => {
      const query = fold(raw.trim());
      visible = rows
        .filter(({ country }) => country.search.includes(query.replace(/^\+/, "")))
        .map((entry) => ({ entry, score: rank(entry.country, query) }))
        .sort((left, right) => left.score - right.score)
        .map(({ entry }) => entry);
      rows.forEach(({ row }) => {
        row.hidden = true;
      });
      visible.forEach(({ row }) => {
        row.hidden = false;
        list.append(row);
      });
      empty.hidden = visible.length > 0;
      status.textContent = visible.length === 0 ? "No countries found" : visible.length + " countries";
      const chosen = selectedCountry();
      const chosenPosition = visible.findIndex(({ country }) => chosen && country === chosen);
      setActive(chosenPosition >= 0 && query === "" ? chosenPosition : visible.length ? 0 : -1);
    };

    const close = (refocus) => {
      panel.hidden = true;
      trigger.setAttribute("aria-expanded", "false");
      if (refocus) {
        trigger.focus();
      }
    };

    // position: fixed, placed from the button's own rectangle: the panel is never clipped by a
    // scrolling ancestor (the "Create user" dialog scrolls) and flips above when the space below is
    // short. Set through the CSSOM, which the CSP (style-src 'self') allows, unlike a style attribute.
    const place = () => {
      const rect = trigger.getBoundingClientRect();
      const room = window.innerHeight - rect.bottom;
      const above = room < PANEL_ROOM && rect.top > room;
      const width = Math.min(PANEL_WIDTH, window.innerWidth - 2 * PANEL_MARGIN);
      panel.style.width = width + "px";
      panel.style.left =
        Math.min(Math.max(PANEL_MARGIN, rect.left), window.innerWidth - width - PANEL_MARGIN) + "px";
      panel.style.top = above ? "auto" : rect.bottom + PANEL_GAP + "px";
      panel.style.bottom = above ? window.innerHeight - rect.top + PANEL_GAP + "px" : "auto";
      list.style.maxHeight =
        Math.max(LIST_MIN, Math.min(LIST_MAX, (above ? rect.top : room) - LIST_CHROME)) + "px";
    };

    const open = (seed) => {
      panel.hidden = false;
      place();
      trigger.setAttribute("aria-expanded", "true");
      search.value = seed || "";
      filter(search.value);
      search.focus();
    };

    const choose = (country) => {
      select.selectedIndex = country.index;
      select.dispatchEvent(new Event("change", { bubbles: true }));
      renderTrigger();
      renderPreview();
      close(false);
      (next || trigger).focus();
    };

    trigger.addEventListener("click", () => (panel.hidden ? open("") : close(true)));
    trigger.addEventListener("keydown", (event) => {
      if (event.key === "ArrowDown" || event.key === "ArrowUp") {
        event.preventDefault();
        open("");
      } else if (event.key.length === 1 && !event.ctrlKey && !event.metaKey && !event.altKey && event.key !== " ") {
        event.preventDefault();
        open(event.key);
      }
    });

    search.addEventListener("input", () => filter(search.value));
    search.addEventListener("keydown", (event) => {
      const last = visible.length - 1;
      if (event.key === "ArrowDown") {
        event.preventDefault();
        setActive(active >= last ? 0 : active + 1);
      } else if (event.key === "ArrowUp") {
        event.preventDefault();
        setActive(active <= 0 ? last : active - 1);
      } else if (event.key === "Home" && event.ctrlKey) {
        event.preventDefault();
        setActive(0);
      } else if (event.key === "End" && event.ctrlKey) {
        event.preventDefault();
        setActive(last);
      } else if (event.key === "Enter") {
        event.preventDefault();
        if (visible[active]) {
          choose(visible[active].country);
        }
      } else if (event.key === "Escape") {
        event.preventDefault();
        event.stopPropagation();
        close(true);
      } else if (event.key === "Tab") {
        close(false);
      }
    });

    // mousedown, not click: the search input's blur must not fire first and close the panel.
    list.addEventListener("mousedown", (event) => event.preventDefault());
    list.addEventListener("click", (event) => {
      const row = event.target.closest(".clavaris-phone-code__option");
      const found = row && rows.find((entry) => entry.row === row);
      if (found) {
        choose(found.country);
      }
    });
    list.addEventListener("mousemove", (event) => {
      const row = event.target.closest(".clavaris-phone-code__option");
      const position = visible.findIndex((entry) => entry.row === row);
      if (position >= 0 && position !== active) {
        setActive(position);
      }
    });

    // A fixed panel would drift away from its button if the page or the dialog scrolled under it.
    const dismissOnMove = (event) => {
      if (!panel.hidden && !panel.contains(event.target)) {
        close(false);
      }
    };
    window.addEventListener("scroll", dismissOnMove, true);
    window.addEventListener("resize", () => close(false));

    document.addEventListener("click", (event) => {
      if (!panel.hidden && !panel.contains(event.target) && !trigger.contains(event.target)) {
        close(false);
      }
    });

    if (next) {
      next.addEventListener("input", renderPreview);
    }

    // The label that pointed at the (now hidden) <select> points at the button instead.
    const label = document.querySelector('label[for="' + baseId + '"]');
    if (label) {
      label.setAttribute("for", trigger.id);
    }

    // After a validation error the server re-renders the form; the code the person had chosen comes
    // back through data-phone-selected (the first option with that dial code).
    const restored = select.dataset.phoneSelected;
    if (restored) {
      const match = countries.find((country) => country.value === restored);
      if (match) {
        select.selectedIndex = match.index;
      }
    }

    select.hidden = true;
    select.setAttribute("aria-hidden", "true");
    select.tabIndex = -1;
    select.after(trigger, panel);
    renderTrigger();
    renderPreview();
  };

  const init = () => document.querySelectorAll("select[data-phone-country-picker]").forEach(enhance);

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", init);
  } else {
    init();
  }
})();

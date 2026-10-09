(() => {
  "use strict";

  // Translation helper: the shared i18n.js when the page loads it, plain English otherwise.
  const t = (text, ...args) =>
    globalThis.clavarisI18n?.t(text, ...args) ??
    args.reduce((out, arg, index) => out.split("{" + index + "}").join(String(arg)), text);

  // The Sessions page states each duration as an amount and a unit (minutes to years) instead of a
  // raw count of minutes. The server stores minutes either way and is the check that counts; this
  // script only makes the form say so as the person types: the limits follow the chosen unit, a
  // value that is out of range is explained, and an amount that is better read in another unit
  // ("1080 minutes") shows what it comes to ("That is 18 hours."). Every message is worded exactly
  // like the server's, so the same mistake never reads two ways.
  //
  // A month is 30 days and a year 365, as on the server. Largest unit first.
  const UNITS = [
    { minutes: 525600, one: "1 year", many: "{0} years" },
    { minutes: 43200, one: "1 month", many: "{0} months" },
    { minutes: 10080, one: "1 week", many: "{0} weeks" },
    { minutes: 1440, one: "1 day", many: "{0} days" },
    { minutes: 60, one: "1 hour", many: "{0} hours" },
    { minutes: 1, one: "1 minute", many: "{0} minutes" },
  ];

  const MOST_PARTS = 3;

  const say = (unit, count) => (count === 1 ? t(unit.one) : t(unit.many, count));

  /** The minutes in the largest unit that states them exactly: "5 minutes", "1 year". */
  const describe = (minutes) => {
    const unit = UNITS.find((candidate) => minutes % candidate.minutes === 0);
    return say(unit, minutes / unit.minutes);
  };

  /** The minutes broken into up to three units, largest first: "1 day, 6 hours". */
  const humanize = (minutes) => {
    const parts = [];
    let left = minutes;
    for (const unit of UNITS) {
      const count = Math.floor(left / unit.minutes);
      if (count > 0 && parts.length < MOST_PARTS) {
        parts.push(say(unit, count));
        left -= count * unit.minutes;
      }
    }
    return parts.join(", ");
  };

  /**
   * Checks one amount against a unit and the bounds, in minutes.
   * Returns {minutes} when it is valid, or {message} saying what is wrong.
   */
  const analyse = (raw, unitMinutes, minimum, maximum) => {
    const text = String(raw ?? "").trim();
    const amount = /^\d{1,9}$/.test(text) ? Number(text) : 0;
    if (amount < 1) {
      return { message: t("Enter a whole number greater than zero.") };
    }
    const minutes = amount * unitMinutes;
    if (minutes < minimum) {
      return { message: t("Must be at least {0}.", describe(minimum)) };
    }
    if (minutes > maximum) {
      return { message: t("Must be at most {0}.", describe(maximum)) };
    }
    return { minutes };
  };

  const parts = (wrapper) => ({
    input: wrapper.querySelector("input"),
    select: wrapper.querySelector("select"),
    total: wrapper.parentElement?.querySelector("[data-duration-total]"),
    error: wrapper.parentElement?.querySelector("[data-duration-error]"),
  });

  // A selector's unit comes from its option; a field with no selector is always minutes.
  const unitMinutes = (select, input) =>
    Number(select?.selectedOptions?.[0]?.dataset.minutes ?? input.dataset.minutesPerUnit ?? 1);

  const limits = (wrapper) => ({
    minimum: Number(wrapper.dataset.minMinutes),
    maximum: Number(wrapper.dataset.maxMinutes),
  });

  // Inactivity may not outlast the session itself: the field says which other one to compare to.
  const comparedTo = (wrapper) => {
    const other = wrapper.dataset.notLongerThan
      ? wrapper.ownerDocument.getElementById(wrapper.dataset.notLongerThan)
      : null;
    return other?.closest?.("[data-duration-field]") ?? null;
  };

  const evaluate = (wrapper) => {
    const { input, select } = parts(wrapper);
    const { minimum, maximum } = limits(wrapper);
    const result = analyse(input.value, unitMinutes(select, input), minimum, maximum);
    const other = comparedTo(wrapper);
    if (result.minutes !== undefined && other) {
      const otherInput = other.querySelector("input");
      const otherResult = analyse(
        otherInput.value,
        unitMinutes(other.querySelector("select"), otherInput),
        limits(other).minimum,
        limits(other).maximum,
      );
      if (otherResult.minutes !== undefined && result.minutes > otherResult.minutes) {
        return { message: t("Can't be longer than the maximum lifetime.") };
      }
    }
    return result;
  };

  const render = (wrapper) => {
    const { input, select, total, error } = parts(wrapper);
    const unit = unitMinutes(select, input);
    const { minimum, maximum } = limits(wrapper);
    // The browser's own limits follow the chosen unit: 3650 days and 10 years are the same ceiling.
    input.min = String(Math.max(1, Math.ceil(minimum / unit)));
    input.max = String(Math.max(Number(input.min), Math.floor(maximum / unit)));

    const result = evaluate(wrapper);
    input.setCustomValidity(result.message ?? "");
    const showError = Boolean(result.message) && wrapper.dataset.touched === "true";
    if (showError) {
      input.setAttribute("aria-invalid", "true");
    } else {
      input.removeAttribute("aria-invalid");
    }
    if (error) {
      error.textContent = showError ? result.message : "";
      error.hidden = !showError;
    }
    if (total) {
      const entered = say(
        UNITS.find((candidate) => candidate.minutes === unit),
        Number(input.value),
      );
      const comesTo = result.minutes === undefined ? "" : humanize(result.minutes);
      const worthSaying = comesTo !== "" && comesTo !== entered;
      total.textContent = worthSaying ? t("That is {0}.", comesTo) : "";
      total.hidden = !worthSaying;
    }
  };

  const renderAll = (root) => {
    root.querySelectorAll("[data-duration-field]").forEach(render);
  };

  // A value is only called wrong once it has been settled (left, or the unit changed); while it is
  // still being typed only the limits and the total follow along.
  const settle = (event) => {
    const wrapper = event.target?.closest?.("[data-duration-field]");
    if (wrapper) {
      wrapper.dataset.touched = "true";
      renderAll(wrapper.closest("form") ?? wrapper.ownerDocument);
    }
  };

  const follow = (event) => {
    const wrapper = event.target?.closest?.("[data-duration-field]");
    if (wrapper) {
      renderAll(wrapper.closest("form") ?? wrapper.ownerDocument);
    }
  };

  document.addEventListener("input", follow);
  document.addEventListener("change", settle);
  document.addEventListener("focusout", settle);
  // A submit the browser refuses fires "invalid" on each offending field; show those messages now.
  document.addEventListener("invalid", settle, true);
  document.addEventListener("DOMContentLoaded", () => renderAll(document));
  document.body?.addEventListener("htmx:afterSwap", () => renderAll(document));

  globalThis.clavarisDuration = { describe, humanize, analyse };
})();

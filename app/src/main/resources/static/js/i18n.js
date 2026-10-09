(() => {
  "use strict";

  // The browser half of the interface's localisation. The server translates every page it renders
  // (see LocalizationDialect); the strings that exist only in scripts (confirmation dialogs, toasts,
  // the phone picker, the picture uploader) are translated here, from the same convention: the
  // English text is the key, a missing entry falls back to it, and {0}, {1} stand for values.
  //
  //   clavarisI18n.t("That image is {0}. The limit is 10 MB.", "12 MB")
  //
  // The language is the page's own: the server writes it to <html lang>. This file loads first (it
  // is in the shared head), so every other script can call it; each of them also carries a small
  // fallback, so a page that does not include this file still works in English.
  //
  // Adding a language: add its table next to SPANISH below and list it in TABLES.

  const SPANISH = {
    // Confirmation dialog
    "Are you sure?": "¿Estás seguro?",
    Confirm: "Confirmar",
    Cancel: "Cancelar",
    // Copy buttons
    Copied: "Copiado",
    "Copy failed": "No se pudo copiar",
    // Failed requests
    "Couldn't reach the server. Check your connection and try again.":
      "No se pudo conectar con el servidor. Revisa tu conexión e inténtalo de nuevo.",
    "That request was rejected. Check what you entered and try again.":
      "Se rechazó la solicitud. Revisa lo que escribiste e inténtalo de nuevo.",
    "Your session has expired. Reload the page and sign in again.":
      "Tu sesión ha caducado. Recarga la página e inicia sesión de nuevo.",
    "You don't have permission to do that.": "No tienes permiso para hacer eso.",
    "That item no longer exists. Reload the page to see the latest.":
      "Ese elemento ya no existe. Recarga la página para ver lo más reciente.",
    "That conflicts with a recent change. Reload the page and try again.":
      "Esto entra en conflicto con un cambio reciente. Recarga la página e inténtalo de nuevo.",
    "That is too large to send.": "Es demasiado grande para enviarlo.",
    "Too many requests. Wait a moment and try again.":
      "Demasiadas solicitudes. Espera un momento e inténtalo de nuevo.",
    "Something went wrong on our side. Try again in a moment.":
      "Algo salió mal de nuestro lado. Inténtalo de nuevo en un momento.",
    "That didn't work. Try again.": "No funcionó. Inténtalo de nuevo.",
    "The request took too long. Try again.": "La solicitud tardó demasiado. Inténtalo de nuevo.",
    Notifications: "Notificaciones",
    "Dismiss notification": "Descartar notificación",
    // Permissions field
    "Selected permissions": "Permisos seleccionados",
    "Add a permission": "Añadir un permiso",
    "Type a permission and press Enter": "Escribe un permiso y pulsa Intro",
    "No permissions assigned.": "No hay permisos asignados.",
    "Remove {0}": "Quitar {0}",
    // Phone number field
    "Search country or code": "Buscar país o código",
    "Search country or dial code": "Buscar país o prefijo",
    Countries: "Países",
    "No country matches that.": "Ningún país coincide.",
    Code: "Código",
    "Country code, {0} {1}. Change": "Prefijo de país, {0} {1}. Cambiar",
    "Country code. Choose": "Prefijo de país. Elegir",
    "Will be saved as {0} {1}": "Se guardará como {0} {1}",
    "No countries found": "No se encontraron países",
    "1 country": "1 país",
    "{0} countries": "{0} países",
    // Profile picture
    "Choose a JPG, PNG, WebP or GIF image.": "Elige una imagen JPG, PNG, WebP o GIF.",
    "That image is {0}. The limit is 10 MB.": "Esa imagen pesa {0}. El límite es de 10 MB.",
    "{0} ({1}) will replace the current picture.": "{0} ({1}) reemplazará la imagen actual.",
    // Pagination
    "Page {0} of {1}": "Página {0} de {1}",
    // Passkeys
    "Something went wrong signing in with your passkey.":
      "Algo salió mal al iniciar sesión con tu clave de acceso.",
    "Something went wrong registering your passkey.":
      "Algo salió mal al registrar tu clave de acceso.",
    // Duration fields (the Sessions page): the same wording the server uses
    "1 minute": "1 minuto",
    "{0} minutes": "{0} minutos",
    "1 hour": "1 hora",
    "{0} hours": "{0} horas",
    "1 day": "1 día",
    "{0} days": "{0} días",
    "1 week": "1 semana",
    "{0} weeks": "{0} semanas",
    "1 month": "1 mes",
    "{0} months": "{0} meses",
    "1 year": "1 año",
    "{0} years": "{0} años",
    "Enter a whole number greater than zero.": "Introduce un número entero mayor que cero.",
    "Must be at least {0}.": "Debe ser como mínimo {0}.",
    "Must be at most {0}.": "Debe ser como máximo {0}.",
    "Can't be longer than the maximum lifetime.": "No puede ser mayor que la duración máxima.",
    "That is {0}.": "Equivale a {0}.",
  };

  const TABLES = { es: SPANISH };

  const language = () =>
    String(document.documentElement?.lang || "en")
      .slice(0, 2)
      .toLowerCase();

  const fill = (text, args) => args.reduce((out, arg, index) => out.split("{" + index + "}").join(String(arg)), text);

  /** The text in the page's language, with {0}, {1}, ... replaced by the arguments. */
  const t = (text, ...args) => {
    const table = TABLES[language()];
    return fill(table && Object.hasOwn(table, text) ? table[text] : text, args);
  };

  /** A number in the page's language: "1,234.5" / "1.234,5". */
  const number = (value, maximumFractionDigits = 0) =>
    new Intl.NumberFormat(language(), { maximumFractionDigits }).format(value);

  /** A file size in decimal units: "10 MB" / "1,5 MB". */
  const size = (bytes) => {
    const units = [
      [1_000_000_000, "GB"],
      [1_000_000, "MB"],
      [1_000, "KB"],
    ];
    const unit = units.find(([threshold]) => bytes >= threshold);
    return unit ? number(bytes / unit[0], 1) + " " + unit[1] : number(bytes) + " B";
  };

  /** A country's name in the page's language, from its ISO 3166 code: "GT" gives "Guatemala". */
  const countryName = (isoCode, fallback) => {
    let name = fallback;
    try {
      name = new Intl.DisplayNames([language()], { type: "region" }).of(isoCode) ?? fallback;
    } catch {
      // An engine without Intl.DisplayNames, or an unknown code: keep the server's name.
    }
    return name;
  };

  globalThis.clavarisI18n = { t, language, number, size, countryName };
})();

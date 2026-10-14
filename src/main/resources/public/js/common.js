/*
 * Wanda AI Assistant - helpers shared by the login page and the app.
 * Author: Saksham Johri
 */

/* ------------------------------------------------------------------ theme */

/*
 * The saved preference is "system", "light" or "dark". The page always carries the theme actually
 * in use as data-theme, so the stylesheet only needs light and dark rules. "system" follows the
 * device and updates live when the device switches (for example at sunset).
 */
const THEME_KEY = "wanda-theme";
const darkQuery = window.matchMedia("(prefers-color-scheme: dark)");

function resolveTheme(pref) {
  return pref === "dark" || pref === "light" ? pref : darkQuery.matches ? "dark" : "light";
}

function applyTheme(pref) {
  const theme = resolveTheme(pref);
  const root = document.documentElement;
  root.dataset.pref = pref;
  root.dataset.theme = theme;
  const meta = document.querySelector('meta[name="theme-color"]');
  if (meta) meta.content = theme === "dark" ? "#0A0506" : "#FBF5F5";
}

/** Saved preference: "dark" (the Wanda look, default), "light" or "system". */
function themePref() {
  try { return localStorage.getItem(THEME_KEY) || "dark"; } catch (e) { return "dark"; }
}

function setTheme(pref) {
  if (!["system", "light", "dark"].includes(pref)) pref = "system";
  try { localStorage.setItem(THEME_KEY, pref); } catch (e) { /* storage unavailable: still apply it */ }
  applyTheme(pref);
}

/* Apply the saved theme before first paint to avoid a flash, and follow the device in "system" mode. */
applyTheme(themePref());
darkQuery.addEventListener("change", () => { if (themePref() === "system") applyTheme("system"); });

/* ------------------------------------------------------------------- mark */

/**
 * Draws the Wanda mark: a sharp scarlet "W" with a gold spark above it.
 * When animate is true the lines draw themselves once (skipped if reduced motion is on).
 */
let markCount = 0;
function buildMark(svg, animate) {
  const gid = "mark-grad-" + ++markCount;
  svg.setAttribute("viewBox", "0 0 120 120");
  svg.innerHTML =
    `<defs><linearGradient id="${gid}" x1="14" y1="40" x2="106" y2="96" gradientUnits="userSpaceOnUse">` +
    '<stop offset="0" stop-color="#FF6E86"/><stop offset="1" stop-color="#E0143C"/></linearGradient></defs>' +
    `<path class="wave" stroke="url(#${gid})" d="M18 42 L42 92 L60 56 L78 92 L102 42"/>` +
    '<path class="spark" d="M60 8 Q60 24 76 24 Q60 24 60 40 Q60 24 44 24 Q60 24 60 8Z"/>';
  const reduced = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
  svg.classList.toggle("draw", animate && !reduced);
}

/* -------------------------------------------------------------------- api */

/** Sends the visitor to the sign-in page when the server says the session is gone. */
function sessionExpired(path, status) {
  if (status === 401 && !path.startsWith("/api/auth/") && !location.pathname.endsWith("login.html")) {
    window.location.href = "/login.html";
  }
}

/** Small fetch wrapper: JSON in, JSON out, throws Error(message) on failure. */
async function api(path, options = {}) {
  const opts = { credentials: "same-origin", ...options };
  if (opts.json !== undefined) {
    opts.body = JSON.stringify(opts.json);
    opts.headers = { "Content-Type": "application/json", ...(opts.headers || {}) };
    delete opts.json;
  }
  let res;
  try {
    res = await fetch(path, opts);
  } catch (e) {
    if (e.name === "AbortError") throw e;
    throw new Error("Can't reach the Wanda server. Check that it is running.");
  }
  const data = await res.json().catch(() => ({}));
  if (!res.ok) {
    sessionExpired(path, res.status);
    const err = new Error(data.error || `Request failed (${res.status})`);
    err.status = res.status;
    throw err;
  }
  return data;
}

/**
 * POSTs JSON and reads the newline-delimited JSON events of the reply as they arrive,
 * calling onEvent(event) for each. Throws like api() if the request is refused up front.
 */
async function apiStream(path, json, onEvent, signal) {
  let res;
  try {
    res = await fetch(path, {
      method: "POST", credentials: "same-origin", signal,
      headers: { "Content-Type": "application/json" }, body: JSON.stringify(json)
    });
  } catch (e) {
    if (e.name === "AbortError") throw e;
    throw new Error("Can't reach the Wanda server. Check that it is running.");
  }
  if (!res.ok) {
    const data = await res.json().catch(() => ({}));
    sessionExpired(path, res.status);
    const err = new Error(data.error || `Request failed (${res.status})`);
    err.status = res.status;
    throw err;
  }
  const reader = res.body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";
  for (;;) {
    const { value, done } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });
    for (let nl; (nl = buffer.indexOf("\n")) >= 0; ) {
      const line = buffer.slice(0, nl).trim();
      buffer = buffer.slice(nl + 1);
      if (line) onEvent(JSON.parse(line));
    }
  }
}

/*
 * Wanda AI Assistant - sign in and create account.
 * Author: Saksham Johri
 */
(function () {
  const $ = (id) => document.getElementById(id);
  let mode = "login";

  buildMark($("mark"), false);

  // Opening the HTML file directly (double-click) can't reach the Java server.
  if (location.protocol === "file:") {
    $("error").textContent = "Start Wanda with ./run.sh in Terminal, then open http://wanda.localhost in Chrome.";
    $("submit").disabled = true;
    return;
  }

  const copy = {
    login: { title: "Welcome back", sub: "Sign in to pick up where you left off.", button: "Sign in" },
    register: { title: "Create your account", sub: "It takes a few seconds. Your chats stay private to you.", button: "Create account" }
  };

  function switchMode(next) {
    mode = next;
    document.querySelectorAll(".tabs button").forEach((b) => b.classList.toggle("active", b.dataset.tab === next));
    $("nameField").hidden = next !== "register";
    $("formTitle").textContent = copy[next].title;
    $("formSub").textContent = copy[next].sub;
    $("submit").textContent = copy[next].button;
    $("password").autocomplete = next === "register" ? "new-password" : "current-password";
    $("error").textContent = "";
  }

  function showError(message) {
    $("error").classList.remove("info");
    $("error").textContent = message;
  }

  /** Neutral (non-error) note under the form. */
  function showNote(message) {
    $("error").classList.add("info");
    $("error").textContent = message;
  }

  document.querySelectorAll(".tabs button").forEach((b) => b.addEventListener("click", () => switchMode(b.dataset.tab)));

  $("togglePw").addEventListener("click", () => {
    const input = $("password");
    input.type = input.type === "password" ? "text" : "password";
  });

  $("authForm").addEventListener("submit", async (e) => {
    e.preventDefault();
    const username = $("username").value.trim();
    const password = $("password").value;
    if (!username || !password) {
      showError("Enter your username and password.");
      return;
    }
    if (mode === "register" && password.length < 6) {
      showError("Use at least 6 characters for your password.");
      return;
    }
    $("submit").disabled = true;
    showError("");
    try {
      await api("/api/auth/" + mode, {
        method: "POST",
        json: { username, password, displayName: $("displayName").value.trim() }
      });
      window.location.href = "/";
    } catch (err) {
      showError(err.message);
      if (err.message.startsWith("No account found")) switchModeKeepingFields("register");
    } finally {
      $("submit").disabled = false;
    }
  });

  /** Moves to "Create account" after a failed sign-in, keeping what was typed. */
  function switchModeKeepingFields(next) {
    const message = $("error").textContent;
    switchMode(next);
    showError(message.replace("Choose Create account to make one.", "Create it below."));
  }

  // Already signed in? Go straight to the app.
  api("/api/auth/status").then((s) => { if (s.signedIn) window.location.href = "/"; }).catch(() => {});
})();

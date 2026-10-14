/*
 * Wanda AI Assistant - front-end application.
 * Streaming chat, history with pinned chats, file and picture uploads, image creation,
 * voice input, read-aloud, hands-free voice chat and settings.
 * Author: Saksham Johri
 */
(function () {
  "use strict";

  const $ = (id) => document.getElementById(id);
  const icon = (name) => `<svg class="i"><use href="#i-${name}"/></svg>`;
  const THINKING = `<div class="thinking" aria-label="Wanda is thinking"><i></i><i></i><i></i></div>`;

  const state = {
    user: null,
    settings: {},
    conversations: [],
    currentId: null,
    pending: [],        // uploaded files waiting to be sent: {id, name, kind, preview, busy}
    sending: false,
    locked: false,      // true while a request cannot be cancelled (creating an image)
    imageMode: false,   // the next message creates a picture instead of a text reply
    temp: false,        // temporary (incognito) chat: nothing is saved
    tempId: null,       // the server's id for the open temporary chat (set after its first message)
    controller: null,
    voices: []
  };

  /* ------------------------------------------------------------------ utils */

  function escapeHtml(s) {
    return String(s).replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
  }

  /** A short message above everything (it is a top-layer popover, so open dialogs cannot hide it). */
  function toast(text, isError) {
    const host = $("toasts");
    const el = document.createElement("div");
    el.className = "toast" + (isError ? " error" : "");
    el.setAttribute("role", "status");
    el.textContent = text;
    host.appendChild(el);
    try {
      if (host.matches(":popover-open")) host.hidePopover();
      host.showPopover(); // re-showing moves it above any dialog opened since
    } catch (e) { /* browsers without popover support just show it in the page */ }
    setTimeout(() => {
      el.remove();
      if (!host.children.length) { try { host.hidePopover(); } catch (e) { /* ignore */ } }
    }, 3600);
  }

  function renderMarkdown(text) {
    if (window.marked && window.DOMPurify) {
      return DOMPurify.sanitize(marked.parse(text, { breaks: true, gfm: true }));
    }
    return "<p>" + escapeHtml(text).replace(/\n/g, "<br>") + "</p>";
  }

  /** Adds syntax highlighting, code copy buttons and safe links to rendered Markdown. */
  function enhance(container) {
    container.querySelectorAll("pre").forEach((pre) => {
      const code = pre.querySelector("code");
      if (code && window.hljs) hljs.highlightElement(code);
      const btn = document.createElement("button");
      btn.className = "copy-code";
      btn.textContent = "Copy";
      btn.onclick = () => copyText((code || pre).innerText, () => { btn.textContent = "Copied"; setTimeout(() => (btn.textContent = "Copy"), 1500); });
      pre.appendChild(btn);
    });
    container.querySelectorAll("a").forEach((a) => { a.target = "_blank"; a.rel = "noopener"; });
  }

  function copyText(text, done) {
    navigator.clipboard.writeText(text).then(done, () => toast("Copy isn't allowed in this browser", true));
  }

  function download(name, text) {
    const a = document.createElement("a");
    a.href = URL.createObjectURL(new Blob([text], { type: "text/markdown" }));
    a.download = name;
    a.click();
    setTimeout(() => URL.revokeObjectURL(a.href), 2000);
  }

  /** Nicer than the browser's confirm(): resolves to true or false. */
  function confirmDialog(title, text, okLabel) {
    return new Promise((resolve) => {
      const dlg = $("confirmDlg");
      $("confirmTitle").textContent = title;
      $("confirmText").textContent = text;
      $("confirmYes").textContent = okLabel || "Delete";
      const finish = (answer) => { dlg.removeEventListener("cancel", onCancel); dlg.close(); resolve(answer); };
      const onCancel = () => finish(false); // Esc key
      dlg.addEventListener("cancel", onCancel);
      $("confirmYes").onclick = () => finish(true);
      $("confirmNo").onclick = () => finish(false);
      dlg.showModal();
      $("confirmNo").focus();
    });
  }

  /* ---------------------------------------------------------------- startup */

  async function init() {
    try {
      state.user = await api("/api/auth/me");
      state.settings = await api("/api/settings");
    } catch (e) {
      window.location.href = "/login.html";
      return;
    }
    setTheme(state.settings.theme);
    renderUser();
    renderModelPill();
    applyVoiceSettings();
    renderWelcome();
    renderComposerMode();
    await loadConversations();
    setupVoices();
    $("input").focus();
  }

  function renderUser() {
    const name = state.user.displayName || state.user.username;
    $("meName").textContent = name;
    $("meUser").textContent = "@" + state.user.username;
    $("avatar").textContent = name.trim().charAt(0).toUpperCase();
  }

  /** Voice is optional: the master switch hides every voice control; voiceReplies mutes Wanda in voice chat. */
  function voiceOn() { return state.settings.voiceFeatures !== "false"; }
  function wandaSpeaks() { return state.settings.voiceReplies !== "false"; }

  function applyVoiceSettings() {
    document.body.dataset.voice = voiceOn() ? "on" : "off";
    renderVoiceMute();
  }

  function renderVoiceMute() {
    const muted = !wandaSpeaks();
    $("voiceMute").classList.toggle("muted", muted);
    $("voiceMute").setAttribute("aria-pressed", String(muted));
    $("voiceMute").querySelector("span").textContent = muted ? "Wanda's voice: off" : "Wanda's voice: on";
  }

  function renderModelPill() {
    const s = state.settings;
    const needsKey = s.aiProvider === "Setup guide";
    $("modelPill").textContent = needsKey ? "Groq key needed" : s.model || "Groq";
    $("modelPill").classList.toggle("warn", needsKey);
  }

  /* ---------------------------------------------------------------- history */

  async function loadConversations() {
    try {
      state.conversations = await api("/api/conversations");
    } catch (e) {
      toast(e.message, true);
    }
    renderHistory();
  }

  /** Pinned chats first, then the most recently active. */
  function sortConversations() {
    state.conversations.sort((a, b) => (b.pinned - a.pinned) || (b.updatedAt - a.updatedAt));
  }

  /** Which ChatGPT-style time group a chat belongs to (by when it was last active). */
  function groupLabel(ts, now) {
    const startToday = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime();
    const day = 86400000;
    if (ts >= startToday) return "Today";
    if (ts >= startToday - day) return "Yesterday";
    if (ts >= startToday - 6 * day) return "Previous 7 days";
    if (ts >= startToday - 29 * day) return "Previous 30 days";
    const d = new Date(ts);
    return d.toLocaleString(undefined, { month: "long", year: d.getFullYear() === now.getFullYear() ? undefined : "numeric" });
  }

  function renderHistory() {
    closeMenu();
    const q = $("search").value.trim().toLowerCase();
    const list = state.conversations.filter((c) => c.title.toLowerCase().includes(q));
    const box = $("history");
    box.innerHTML = "";
    if (!list.length) {
      box.innerHTML = `<div class="empty-history">${q ? "No chats match your search." : "Your chats will appear here.<br>Start one with New chat."}</div>`;
      return;
    }
    const now = new Date();
    let lastLabel = null;
    list.forEach((c) => {
      const label = c.pinned ? "Pinned" : groupLabel(c.updatedAt, now);
      if (label !== lastLabel) {
        const h = document.createElement("div");
        h.className = "history-label";
        h.innerHTML = (c.pinned ? icon("pin") : "") + `<span></span>`;
        h.querySelector("span").textContent = label;
        box.appendChild(h);
        lastLabel = label;
      }
      box.appendChild(chatRow(c));
    });
  }

  function chatRow(c) {
    const row = document.createElement("div");
    row.className = "conv" + (c.id === state.currentId ? " active" : "");
    row.dataset.id = c.id;
    row.innerHTML = `<button class="title"></button>${c.pinned ? icon("pin").replace('class="i"', 'class="i pin-mark"') : ""}
      <button class="icon-btn more" aria-haspopup="menu" aria-label="Chat options" title="Options">${icon("more")}</button>`;
    const title = row.querySelector(".title");
    title.textContent = c.title;
    title.title = c.title;
    title.onclick = () => openConversation(c.id);
    row.querySelector(".more").onclick = (e) => { e.stopPropagation(); openMenu(c, row, e.currentTarget); };
    row.addEventListener("contextmenu", (e) => { e.preventDefault(); openMenu(c, row, row.querySelector(".more")); });
    return row;
  }

  /* The ⋯ menu of a chat: pin, rename, delete */
  let menuConv = null;

  function openMenu(conv, row, anchor) {
    closeMenu();
    menuConv = conv;
    const menu = $("convMenu");
    menu.querySelector("[data-act=pin] span").textContent = conv.pinned ? "Unpin chat" : "Pin chat";
    menu.hidden = false;
    const r = anchor.getBoundingClientRect();
    const w = menu.offsetWidth, h = menu.offsetHeight;
    menu.style.left = Math.max(8, Math.min(r.left, window.innerWidth - w - 8)) + "px";
    menu.style.top = (r.bottom + h + 8 > window.innerHeight ? Math.max(8, r.top - h - 4) : r.bottom + 4) + "px";
    row.classList.add("menu-open");
    menu.querySelector("button").focus();
  }

  function closeMenu() {
    $("convMenu").hidden = true;
    document.querySelectorAll(".conv.menu-open").forEach((r) => r.classList.remove("menu-open"));
    menuConv = null;
  }

  async function togglePin(conv) {
    try {
      const updated = await api("/api/conversations/" + conv.id, { method: "PUT", json: { pinned: !conv.pinned } });
      conv.pinned = updated.pinned;
      sortConversations();
      renderHistory();
      toast(conv.pinned ? "Chat pinned" : "Chat unpinned");
    } catch (e) { toast(e.message, true); }
  }

  function startRename(row, conv) {
    const input = document.createElement("input");
    input.value = conv.title;
    input.setAttribute("aria-label", "Chat name");
    row.innerHTML = "";
    row.appendChild(input);
    input.focus();
    input.select();
    let done = false;
    const finish = async (save) => {
      if (done) return;
      done = true;
      const title = input.value.trim();
      if (save && title && title !== conv.title) {
        try {
          const updated = await api("/api/conversations/" + conv.id, { method: "PUT", json: { title } });
          conv.title = updated.title;
          if (conv.id === state.currentId) $("chatTitle").textContent = conv.title;
        } catch (e) { toast(e.message, true); }
      }
      renderHistory();
    };
    input.onkeydown = (e) => { if (e.key === "Enter") finish(true); if (e.key === "Escape") finish(false); };
    input.onblur = () => finish(true);
  }

  async function deleteConversation(conv) {
    if (!(await confirmDialog("Delete chat?", `"${conv.title}" will be deleted for good. This can't be undone.`))) return;
    try {
      await api("/api/conversations/" + conv.id, { method: "DELETE" });
      state.conversations = state.conversations.filter((c) => c.id !== conv.id);
      if (conv.id === state.currentId) newChat();
      renderHistory();
      toast("Chat deleted");
    } catch (e) { toast(e.message, true); }
  }

  async function openConversation(id) {
    if (state.sending) { toast("Wait for the reply to finish first"); return; }
    leaveTemporary();
    closeNav();
    state.currentId = id;
    const conv = state.conversations.find((c) => c.id === id);
    $("chatTitle").textContent = conv ? conv.title : "Chat";
    renderHistory();
    $("messages").innerHTML = "";
    try {
      const msgs = await api("/api/conversations/" + id);
      if (state.currentId !== id) return; // user switched again meanwhile
      msgs.forEach((m) => {
        if (m.role === "USER") addMessage("user", m.content, m.attachments, m.kind === "image");
        else if (m.kind === "image") addImageReply(addMessage("bot", null), m.id, m.content);
        else addMessage("bot", m.content);
      });
      refreshRegenerate();
      scrollDown(true);
    } catch (e) {
      toast(e.message, true);
    }
  }

  function newChat() {
    if (state.sending) { toast("Wait for the reply to finish first"); return; }
    leaveTemporary();
    closeNav();
    state.currentId = null;
    $("chatTitle").textContent = "New chat";
    renderHistory();
    renderWelcome();
    $("input").focus();
  }

  /** Downloads the open chat as a Markdown file. */
  async function exportChat() {
    if (state.temp) { toast("Temporary chats can't be exported. Copy what you need before you leave."); return; }
    if (!state.currentId) { toast("Start a chat first, then export it"); return; }
    try {
      const msgs = await api("/api/conversations/" + state.currentId);
      const title = $("chatTitle").textContent;
      const me = state.user.displayName || state.user.username;
      const body = msgs.map((m) => {
        const who = m.role === "USER" ? me : "Wanda";
        const text = m.kind === "image" ? (m.role === "USER" ? m.content : `[Image: ${m.content}]`) : m.content;
        return `**${who}:**\n\n${text}`;
      }).join("\n\n---\n\n");
      download((title.replace(/[^\w -]+/g, "").slice(0, 50).trim() || "wanda-chat") + ".md", `# ${title}\n\n${body}\n`);
    } catch (e) { toast(e.message, true); }
  }

  /* --------------------------------------------------------------- messages */

  function renderWelcome() {
    const hour = new Date().getHours();
    const part = hour < 12 ? "Good morning" : hour < 17 ? "Good afternoon" : "Good evening";
    const first = (state.user.displayName || state.user.username).split(/[ @]/)[0];
    if (state.temp) {
      $("messages").innerHTML = `
        <div class="welcome temp">
          <div class="orb"><svg class="mark" id="welcomeMark" aria-hidden="true"></svg></div>
          <h2>Temporary chat</h2>
          <p>This conversation isn't saved to your history and Wanda won't keep it. Files and replies are forgotten when you leave this chat.</p>
        </div>`;
      buildMark($("welcomeMark"), true);
      return;
    }
    $("messages").innerHTML = `
      <div class="welcome">
        <div class="orb"><svg class="mark" id="welcomeMark" aria-hidden="true"></svg></div>
        <h2>${escapeHtml(part)}, <span>${escapeHtml(first)}</span></h2>
        <p>Ask anything, attach a file, create an image, or start a voice chat.</p>
        <div class="starters">
          <button class="starter" data-text="Explain how JDBC talks to a database, step by step, with a short Java example.">
            ${icon("book")}<div><b>Explain a concept</b><span>How JDBC talks to a database</span></div></button>
          <button class="starter" data-kind="file">
            ${icon("file")}<div><b>Summarise a document</b><span>PDF, Word, PowerPoint, Excel or text</span></div></button>
          <button class="starter" data-text="Write a Java method that checks whether a string is a palindrome. Include comments and two test cases.">
            ${icon("code")}<div><b>Write some code</b><span>A palindrome checker in Java</span></div></button>
          <button class="starter" data-kind="image">
            ${icon("image")}<div><b>Create an image</b><span>Describe it and Wanda draws it</span></div></button>
          <button class="starter" data-text="Write a short, polite email to my professor asking for a two-day extension on my project.">
            ${icon("edit")}<div><b>Draft an email</b><span>Ask for a project extension</span></div></button>
          <button class="starter" data-text="Help me plan a realistic one-week study schedule for my upcoming exams. Ask me what you need to know first.">
            ${icon("check")}<div><b>Plan my week</b><span>A study schedule that works</span></div></button>
        </div>
      </div>`;
    buildMark($("welcomeMark"), true);
    document.querySelectorAll(".starter").forEach((b) => b.addEventListener("click", () => {
      if (b.dataset.kind === "file") { $("fileInput").click(); return; }
      if (b.dataset.kind === "image") { setImageMode(true); return; }
      $("input").value = b.dataset.text;
      send();
    }));
  }

  /** Adds a message bubble. For user messages `attachments` is the comma separated file names. */
  function addMessage(who, text, attachments, isImageRequest) {
    const welcome = document.querySelector(".welcome");
    if (welcome) welcome.remove();
    const row = document.createElement("div");
    row.className = "msg " + who;
    if (who === "user") {
      const tags = [];
      if (isImageRequest) tags.push(`<span class="file-tag">${icon("image")}Image request</span>`);
      if (attachments) attachments.split(", ").forEach((n) => tags.push(`<span class="file-tag">${icon("file")}${escapeHtml(n)}</span>`));
      row.innerHTML = `<div class="body-wrap">${tags.length ? `<div class="files">${tags.join("")}</div>` : ""}<div class="body"></div></div>`;
      row.querySelector(".body").textContent = text;
    } else {
      row.innerHTML = `<img class="bot-mark" src="/img/logo.svg" alt=""><div class="body"><div class="md"></div></div>`;
      if (text !== null) fillBot(row, text);
    }
    $("messages").appendChild(row);
    return row;
  }

  /** Renders a finished assistant reply with its actions: copy and read aloud. */
  function fillBot(row, text) {
    row.classList.remove("streaming");
    const md = row.querySelector(".md");
    md.innerHTML = renderMarkdown(text);
    enhance(md);
    row.querySelectorAll(".msg-actions").forEach((a) => a.remove());
    const actions = document.createElement("div");
    actions.className = "msg-actions";
    actions.innerHTML = `<button class="icon-btn" title="Copy" aria-label="Copy">${icon("copy")}</button>
                         <button class="icon-btn" data-speak title="Read aloud" aria-label="Read aloud">${icon("speak")}</button>`;
    const [copy, speakBtn] = actions.children;
    copy.onclick = () => copyText(text, () => { copy.innerHTML = icon("check"); setTimeout(() => (copy.innerHTML = icon("copy")), 1500); });
    speakBtn.onclick = () => speak(text, speakBtn);
    row.querySelector(".body").appendChild(actions);
  }

  /** Puts a "Regenerate" button on the latest reply only (for pictures: "Create another"). */
  function refreshRegenerate() {
    document.querySelectorAll(".regen").forEach((b) => b.remove());
    const bots = document.querySelectorAll(".msg.bot .msg-actions");
    if (!bots.length || (!state.currentId && !state.tempId)) return;
    const last = bots[bots.length - 1];
    const row = last.closest(".msg");
    const btn = document.createElement("button");
    btn.className = "icon-btn regen";
    btn.type = "button";
    btn.innerHTML = icon("retry");
    if (row.dataset.kind === "image") {
      btn.title = "Create another";
      btn.setAttribute("aria-label", "Create another image");
      btn.onclick = () => createImage(row.dataset.prompt);
    } else {
      btn.title = "Regenerate";
      btn.setAttribute("aria-label", "Regenerate reply");
      btn.onclick = regenerate;
    }
    last.appendChild(btn);
  }

  /** A reply made of a picture (served from /api/image/{messageId}). */
  function addImageReply(row, messageId, prompt) {
    row.dataset.kind = "image";
    row.dataset.prompt = prompt;
    const url = "/api/image/" + messageId;
    const body = row.querySelector(".body");
    body.innerHTML = `<figure class="gen"><a href="${url}" target="_blank" rel="noopener"><img alt="" loading="lazy"></a><figcaption></figcaption></figure>
      <div class="msg-actions"><a class="icon-btn" href="${url}" download="wanda-image" title="Download" aria-label="Download image">${icon("download")}</a></div>`;
    body.querySelector("img").src = url;
    body.querySelector("img").alt = prompt;
    body.querySelector("figcaption").textContent = prompt;
  }

  /* -------------------------------------------------- temporary (incognito) chat */

  /** Asks the server to forget the open temporary chat right away (it also expires on its own). */
  function discardTemporary() {
    if (state.tempId) {
      fetch("/api/chat/temp/" + state.tempId, { method: "DELETE", credentials: "same-origin", keepalive: true }).catch(() => {});
    }
    state.tempId = null;
  }

  function renderTemporaryMode() {
    document.body.dataset.temp = state.temp ? "1" : "";
    $("tempBanner").hidden = !state.temp;
    $("tempBtn").classList.toggle("on", state.temp);
    $("tempBtn").setAttribute("aria-pressed", String(state.temp));
    $("input").placeholder = state.temp ? "Message Wanda (temporary chat)" : state.imageMode ? "Describe the image you want to create…" : "Message Wanda";
  }

  /** Switches back to normal chats (used by New chat, opening a saved chat, and the ghost button). */
  function leaveTemporary() {
    if (!state.temp) return;
    discardTemporary();
    state.temp = false;
    renderTemporaryMode();
  }

  /** Starts a fresh temporary chat, or leaves the current one. */
  function toggleTemporary() {
    if (state.sending) { toast("Wait for the reply to finish first"); return; }
    if (state.temp) { newChat(); return; }
    closeNav();
    setImageMode(false);
    state.temp = true;
    state.tempId = null;
    state.currentId = null;
    $("chatTitle").textContent = "Temporary chat";
    renderTemporaryMode();
    renderHistory();
    renderWelcome();
    $("input").focus();
  }

  /* ------------------------------------------------------------ scrolling */

  let stick = true; // follow new text unless the reader scrolled up

  function scrollDown(force) {
    const t = $("thread");
    if (force) stick = true;
    if (stick) t.scrollTop = t.scrollHeight;
    $("toBottom").hidden = stick;
  }

  $("thread").addEventListener("scroll", () => {
    const t = $("thread");
    stick = t.scrollHeight - t.scrollTop - t.clientHeight < 90;
    $("toBottom").hidden = stick || t.scrollHeight <= t.clientHeight;
  });
  $("toBottom").onclick = () => { stick = true; $("thread").scrollTo({ top: $("thread").scrollHeight, behavior: "smooth" }); $("toBottom").hidden = true; };

  /* ------------------------------------------------------------------- send */

  /**
   * Reads one streamed reply into botRow, showing the text as it arrives.
   * Resolves to what happened: { done, error, aborted, text, started }.
   */
  async function runStream(payload, botRow, speaker, onStart) {
    const out = { done: null, error: null, aborted: false, text: "", started: false };
    const md = botRow.querySelector(".md");
    let queued = false;
    const paint = () => { queued = false; md.innerHTML = renderMarkdown(out.text); scrollDown(); };
    try {
      await apiStream("/api/chat/stream", payload, (ev) => {
        if (ev.type === "start") {
          out.started = true;
          if (onStart) onStart(ev);
        } else if (ev.type === "delta") {
          out.text += ev.text;
          if (speaker) speaker.push(ev.text);
          if (!queued) { queued = true; requestAnimationFrame(paint); }
        } else if (ev.type === "done") {
          out.done = ev;
        } else if (ev.type === "error") {
          out.error = new Error(ev.message);
          out.error.status = ev.status;
        }
      }, state.controller.signal);
      if (!out.done && !out.error) out.error = new Error("The connection was interrupted before the reply finished.");
    } catch (e) {
      if (e.name === "AbortError") out.aborted = true; else out.error = e;
    }
    return out;
  }

  /** Marks a half-finished reply as such, keeping the text that arrived. */
  function finishPartial(botRow, text, note) {
    fillBot(botRow, text);
    const n = document.createElement("div");
    n.className = "stopped-note";
    n.textContent = note;
    botRow.querySelector(".body").insertBefore(n, botRow.querySelector(".msg-actions"));
  }

  /**
   * Sends the composer's text and files and streams the reply.
   * Resolves to the reply text, or null if nothing was sent or it failed.
   * `speaker` (voice chat) is fed the text as it arrives so Wanda starts talking right away.
   */
  async function send(speaker) {
    if (state.sending) return null;
    const raw = $("input").value.trim();
    const imageCommand = raw.match(/^\/(?:image|imagine|draw)\s+([\s\S]+)/i);
    if (raw && (state.imageMode || imageCommand)) return createImage(imageCommand ? imageCommand[1].trim() : raw);

    const ready = state.pending.filter((p) => !p.busy);
    if (state.pending.some((p) => p.busy)) { toast("Wait for your files to finish uploading"); return null; }
    if (!raw && !ready.length) return null;

    const text = raw;
    const files = ready.slice();
    const previousId = state.currentId;
    $("input").value = "";
    autosize();
    state.pending = [];
    renderPending();

    const shown = text || "Please look at the attached file" + (files.length > 1 ? "s." : ".");
    const userRow = addMessage("user", shown, files.map((f) => f.name).join(", "));
    const botRow = addMessage("bot", null);
    botRow.classList.add("streaming");
    botRow.querySelector(".md").innerHTML = THINKING;
    scrollDown(true);
    setSending(true);

    const payload = state.temp
      ? { temporary: true, tempId: state.tempId, message: text, attachmentIds: files.map((f) => f.id) }
      : { conversationId: state.currentId, message: text, attachmentIds: files.map((f) => f.id) };
    const result = await runStream(payload, botRow, speaker, (ev) => {
      if (ev.temporary) { state.tempId = ev.tempId; return; }
      state.currentId = ev.conversation.id;
      $("chatTitle").textContent = ev.conversation.title;
      if (previousId === null) { state.conversations.unshift(ev.conversation); sortConversations(); renderHistory(); }
    });

    try {
      if (result.done) {
        const reply = result.done.reply.content;
        fillBot(botRow, reply);
        if (!state.temp) touchConversation(result.done.conversation);
        if (!voiceChat.on && voiceOn() && state.settings.autoSpeak === "true") speak(reply);
        return reply;
      }
      if (result.aborted) {
        if (result.text.trim()) {
          finishPartial(botRow, result.text, "Stopped");
        } else {            // nothing arrived, so the server undid the exchange: put the message back
          userRow.remove();
          botRow.remove();
          $("input").value = text;
          state.pending = files;
          renderPending();
          autosize();
          if (!state.conversations.some((c) => c.id === state.currentId)) state.currentId = previousId;
        }
        if (!state.temp) await syncAfterStop(previousId);
        return null;
      }
      // The server reported an error (or the connection dropped)
      if (result.text.trim()) {
        finishPartial(botRow, result.text, result.error.message);
        if (!state.temp) await syncAfterStop(previousId);
      } else {
        showSendError(userRow, botRow, result.error, text, files, previousId, speaker);
        if (!state.temp) {
          state.currentId = previousId;
          await syncAfterStop(previousId);
        }
      }
      return null;
    } finally {
      setSending(false);
      refreshRegenerate();
      scrollDown();
    }
  }

  /** After a stop or failure, make the sidebar match the server (a new chat may have been undone). */
  async function syncAfterStop(previousId) {
    await loadConversations();
    if (state.currentId !== null && !state.conversations.some((c) => c.id === state.currentId)) state.currentId = previousId;
    $("chatTitle").textContent = (state.conversations.find((c) => c.id === state.currentId) || { title: "New chat" }).title;
    renderHistory();
  }

  function showSendError(userRow, botRow, error, text, files, previousId, speaker) {
    const md = botRow.querySelector(".md");
    botRow.classList.remove("streaming");
    md.innerHTML = `<div class="error-line"><span></span><button class="btn" type="button">${icon("retry")}Retry</button></div>`;
    md.querySelector("span").textContent = error.message;
    md.querySelector("button").onclick = () => {
      userRow.remove();
      botRow.remove();
      $("input").value = text;
      state.pending = files;
      renderPending();
      send(speaker);
    };
  }

  /** Moves a chat to the top of its group after new activity and refreshes the sidebar. */
  function touchConversation(serverConv) {
    let c = state.conversations.find((x) => x.id === serverConv.id);
    if (!c) { c = serverConv; state.conversations.unshift(c); }
    c.updatedAt = Date.now();
    c.title = serverConv.title;
    c.pinned = serverConv.pinned;
    sortConversations();
    renderHistory();
  }

  async function regenerate() {
    if (state.sending) return;
    const rows = document.querySelectorAll(".msg.bot");
    const row = rows[rows.length - 1];
    const before = row.querySelector(".md").innerHTML;
    row.querySelectorAll(".msg-actions").forEach((a) => a.remove());
    row.classList.add("streaming");
    row.querySelector(".md").innerHTML = THINKING;
    scrollDown(true);
    setSending(true);
    const result = await runStream(state.temp ? { temporary: true, tempId: state.tempId, regenerate: true }
                                              : { conversationId: state.currentId, regenerate: true }, row, null);
    try {
      if (result.done) {
        fillBot(row, result.done.reply.content);
        if (!state.temp) touchConversation(result.done.conversation);
      } else if (result.text.trim()) {
        finishPartial(row, result.text, result.aborted ? "Stopped" : result.error.message);
      } else {
        row.classList.remove("streaming");
        row.querySelector(".md").innerHTML = before;
        if (!result.aborted) toast(result.error.message, true);
        if (!state.temp) await openConversationQuietly(state.currentId);
      }
    } finally {
      setSending(false);
      refreshRegenerate();
    }
  }

  /** Reloads the open chat from the server without switching away. */
  async function openConversationQuietly(id) {
    try {
      const msgs = await api("/api/conversations/" + id);
      $("messages").innerHTML = "";
      msgs.forEach((m) => {
        if (m.role === "USER") addMessage("user", m.content, m.attachments, m.kind === "image");
        else if (m.kind === "image") addImageReply(addMessage("bot", null), m.id, m.content);
        else addMessage("bot", m.content);
      });
    } catch (e) { toast(e.message, true); }
  }

  function setSending(on, locked) {
    state.sending = on;
    state.locked = on && !!locked;
    state.controller = on ? new AbortController() : null;
    const btn = $("send");
    btn.innerHTML = on ? (state.locked ? `<span class="spin"></span>` : icon("stop")) : icon("up");
    btn.setAttribute("aria-label", on ? (state.locked ? "Working" : "Stop") : "Send");
    btn.type = on ? "button" : "submit";
    updateSendState();
  }

  function updateSendState() {
    $("send").disabled = state.locked || (!state.sending && !$("input").value.trim() && !state.pending.length);
  }

  function autosize() {
    const t = $("input");
    t.style.height = "auto";
    t.style.height = Math.min(t.scrollHeight, 220) + "px";
    updateSendState();
  }

  /* ----------------------------------------------------------------- images */

  function setImageMode(on) {
    if (on && state.temp) {
      toast("Image creation isn't available in a temporary chat");
      return;
    }
    if (on && !state.settings.imageReady) {
      toast("Image creation needs a free Cloudflare key. Add it in Settings → Images.", true);
      openSettings("images");
      return;
    }
    state.imageMode = on;
    renderComposerMode();
    if (on) $("input").focus();
  }

  function renderComposerMode() {
    $("modeChip").hidden = !state.imageMode;
    $("imageMode").classList.toggle("on", state.imageMode);
    $("imageMode").setAttribute("aria-pressed", String(state.imageMode));
    $("input").placeholder = state.temp ? "Message Wanda (temporary chat)" : state.imageMode ? "Describe the image you want to create…" : "Message Wanda";
  }

  /** Creates a picture from a description and shows it in the chat. Resolves to a short note, or null on failure. */
  async function createImage(prompt) {
    if (state.sending) return null;
    if (state.temp) { toast("Image creation isn't available in a temporary chat"); return null; }
    if (!state.settings.imageReady) { setImageMode(true); return null; }
    const previousId = state.currentId;
    $("input").value = "";
    state.imageMode = false;
    renderComposerMode();
    autosize();

    const userRow = addMessage("user", prompt, null, true);
    const botRow = addMessage("bot", null);
    botRow.querySelector(".md").innerHTML = `<div class="gen-skeleton"><span>Creating your image…</span></div>`;
    scrollDown(true);
    setSending(true, true);
    try {
      const res = await api("/api/image", { method: "POST", json: { conversationId: state.currentId, prompt } });
      state.currentId = res.conversation.id;
      $("chatTitle").textContent = res.conversation.title;
      addImageReply(botRow, res.reply.id, prompt);
      touchConversation(res.conversation);
      return "I created the image.";
    } catch (e) {
      const md = botRow.querySelector(".md");
      md.innerHTML = `<div class="error-line"><span></span><button class="btn" type="button">${icon("retry")}Try again</button></div>`;
      md.querySelector("span").textContent = e.message;
      md.querySelector("button").onclick = () => { userRow.remove(); botRow.remove(); createImage(prompt); };
      state.currentId = previousId;
      return null;
    } finally {
      setSending(false);
      refreshRegenerate();
      scrollDown();
    }
  }

  /* ---------------------------------------------------------------- uploads */

  async function uploadFiles(fileList) {
    for (const file of fileList) {
      if (file.size > 10 * 1024 * 1024) { toast(file.name + " is larger than 10 MB", true); continue; }
      const isImage = file.type.startsWith("image/");
      if (isImage && state.pending.filter((p) => p.kind === "IMAGE").length >= 3) { toast("You can attach up to 3 pictures per message", true); continue; }
      const item = { id: null, name: file.name, kind: isImage ? "IMAGE" : "TEXT", busy: true, preview: null };
      state.pending.push(item);
      renderPending();
      try {
        const res = await api("/api/upload", {
          method: "POST",
          headers: { "X-File-Name": encodeURIComponent(file.name), "Content-Type": "application/octet-stream" },
          body: file
        });
        Object.assign(item, res, { busy: false });
      } catch (e) {
        state.pending = state.pending.filter((p) => p !== item);
        toast(e.message, true);
      }
      renderPending();
    }
  }

  function renderPending() {
    const box = $("pending");
    box.hidden = !state.pending.length;
    box.innerHTML = "";
    state.pending.forEach((p) => {
      const chip = document.createElement("div");
      chip.className = "chip" + (p.busy ? " busy" : "");
      chip.innerHTML = (p.preview ? `<img src="${p.preview}" alt="">` : icon("file")) +
        `<span></span><button type="button" class="icon-btn" aria-label="Remove file">${icon("x")}</button>`;
      chip.querySelector("span").textContent = p.busy ? "Uploading " + p.name : p.name;
      chip.querySelector("button").onclick = () => {
        state.pending = state.pending.filter((x) => x !== p);
        if (p.id) api("/api/upload/" + p.id, { method: "DELETE" }).catch(() => {});
        renderPending();
      };
      box.appendChild(chip);
    });
    updateSendState();
  }

  /* ------------------------------------------------------------------ voice */

  const Recognition = window.SpeechRecognition || window.webkitSpeechRecognition;
  let recognizer = null;

  /** Microphone button: dictates into the message box. */
  function toggleMic() {
    if (!voiceOn()) return;
    if (!Recognition) { toast("Voice input needs Chrome, Edge or Safari", true); return; }
    if (recognizer) { recognizer.stop(); return; }
    recognizer = new Recognition();
    recognizer.lang = navigator.language || "en-US";
    recognizer.interimResults = true;
    recognizer.continuous = false;
    const base = $("input").value ? $("input").value.trim() + " " : "";
    recognizer.onresult = (e) => {
      const said = Array.from(e.results).map((r) => r[0].transcript).join("");
      $("input").value = base + said;
      autosize();
    };
    recognizer.onerror = (e) => {
      if (e.error === "not-allowed") toast("Allow microphone access to use voice input", true);
      else if (e.error !== "no-speech" && e.error !== "aborted") toast("Voice input error: " + e.error, true);
    };
    recognizer.onend = () => {
      recognizer = null;
      $("mic").classList.remove("listening");
      $("input").focus();
    };
    recognizer.start();
    $("mic").classList.add("listening");
  }

  /** Text with Markdown removed, in a form that sounds right when spoken. */
  function cleanForSpeech(text) {
    return text.replace(/```[\s\S]*?```/g, " Code block omitted. ").replace(/\(https?:[^)]+\)/g, "")
               .replace(/https?:\/\/\S+/g, "link").replace(/[#*_`>|~\[\]]/g, "").replace(/\s+\n/g, "\n");
  }

  /**
   * Speaks a reply sentence by sentence while it is still being written, so a voice chat answer
   * starts within a moment instead of after the whole reply. push() takes new text, finish() says
   * the rest, cancel() stops. idle resolves when everything queued has been spoken (or cancelled).
   */
  function createSpeaker() {
    let pending = "", queued = 0, finished = false, cancelled = false, spokenAny = false;
    let resolveIdle;
    const idle = new Promise((r) => (resolveIdle = r));
    const check = () => { if ((finished && queued === 0) || cancelled) resolveIdle(); };

    function utter(text) {
      if (cancelled || !text.trim()) return;
      const u = new SpeechSynthesisUtterance(text);
      const voice = state.voices.find((v) => v.name === (state.settings.voice || ""));
      if (voice) u.voice = voice;
      queued++;
      spokenAny = true;
      const done = () => { queued--; check(); };
      u.onend = done;
      u.onerror = done;
      speechSynthesis.speak(u);
    }

    /** Speaks every finished sentence of the pending text; with `all`, speaks the rest as well. */
    function drain(all) {
      if ((pending.match(/```/g) || []).length % 2 === 1 && !all) return; // inside a code block: wait for its end
      pending = cleanForSpeech(pending);
      let end = 0;
      if (all) {
        end = pending.length;
      } else {
        const re = /[.!?]+["')\]]*\s|\n/g;
        for (let m; (m = re.exec(pending)); ) end = m.index + m[0].length;
        if (end < 12) return;
      }
      utter(pending.slice(0, end));
      pending = pending.slice(end);
    }

    return {
      push(piece) { if (!finished && !cancelled) { pending += piece; drain(false); } },
      finish() { finished = true; drain(true); if (!spokenAny) queued = 0; check(); },
      cancel() { cancelled = true; pending = ""; if ("speechSynthesis" in window) speechSynthesis.cancel(); resolveIdle(); },
      idle
    };
  }

  /** Stand-in for createSpeaker() when Wanda's voice is off: the reply is shown, not spoken. */
  function createReadOnlySpeaker() {
    let resolveIdle;
    const idle = new Promise((r) => (resolveIdle = r));
    return {
      push() {},
      finish() { setTimeout(resolveIdle, 1500); },   // a moment to read before the microphone opens again
      cancel() { resolveIdle(); },
      idle
    };
  }

  /*
   * Hands-free voice chat: listen, send what was said, speak the reply as it is written, listen again.
   * Ends when the user closes it or nothing is heard several times in a row.
   */
  const voiceChat = { on: false, silentRounds: 0, turn: 0, speaker: null };

  function startVoiceChat() {
    if (!voiceOn()) { toast("Voice features are turned off in Settings → Voice"); return; }
    if (!Recognition || !("speechSynthesis" in window)) { toast("Voice chat needs Chrome, Edge or Safari", true); return; }
    if (state.sending) return;
    if (recognizer) recognizer.abort();
    voiceChat.on = true;
    voiceChat.silentRounds = 0;
    $("voiceOverlay").hidden = false;
    buildMark($("voiceMark"), false);
    listenOnce();
  }

  function stopVoiceChat() {
    voiceChat.on = false;
    voiceChat.turn++;
    $("voiceOverlay").hidden = true;
    if (recognizer) recognizer.abort();
    if (voiceChat.speaker) voiceChat.speaker.cancel();
    if ("speechSynthesis" in window) speechSynthesis.cancel();
    $("input").focus();
  }

  function voiceStatus(name, text) {
    $("voiceOverlay").dataset.state = name;
    $("voiceStatus").textContent = text;
  }

  function listenOnce() {
    if (!voiceChat.on) return;
    const turn = ++voiceChat.turn;
    voiceStatus("listening", "Listening…");
    $("voiceHeard").textContent = "Say something. Wanda answers out loud.";
    let said = "";
    const r = new Recognition();
    r.lang = navigator.language || "en-US";
    r.interimResults = true;
    r.continuous = false;
    r.onresult = (e) => {
      said = Array.from(e.results).map((x) => x[0].transcript).join("");
      $("voiceHeard").textContent = said;
    };
    r.onerror = (e) => {
      if (["not-allowed", "service-not-allowed", "audio-capture"].includes(e.error)) {
        toast("Allow microphone access to use voice chat", true);
        stopVoiceChat();
      }
    };
    r.onend = async () => {
      recognizer = null;
      if (!voiceChat.on || voiceChat.turn !== turn) return;
      if (!said.trim()) {
        if (++voiceChat.silentRounds >= 4) { toast("Voice chat ended because nothing was heard"); stopVoiceChat(); return; }
        setTimeout(listenOnce, 300);
        return;
      }
      voiceChat.silentRounds = 0;
      voiceStatus("thinking", "Thinking…");
      $("input").value = said.trim();
      const speaker = wandaSpeaks() ? createSpeaker() : createReadOnlySpeaker();
      voiceChat.speaker = speaker;
      let shown = "";
      const original = speaker.push;
      speaker.push = (piece) => {       // show the reply on screen while it is spoken
        if ($("voiceOverlay").dataset.state !== "speaking") voiceStatus("speaking", wandaSpeaks() ? "Speaking…" : "Replying…");
        shown += piece;
        const plain = cleanForSpeech(shown).replace(/\s+/g, " ").trim();
        $("voiceHeard").textContent = plain.length > 200 ? "…" + plain.slice(-197) : plain;
        original(piece);
      };
      const reply = await send(speaker);
      if (!voiceChat.on || voiceChat.turn !== turn) { speaker.cancel(); return; }
      if (!reply) { speaker.cancel(); setTimeout(listenOnce, 800); return; }
      speaker.finish();
      await speaker.idle;
      if (!voiceChat.on || voiceChat.turn !== turn) return; // ended, or the user tapped to talk
      listenOnce();
    };
    recognizer = r;
    r.start();
  }

  function setupVoices() {
    if (!("speechSynthesis" in window)) return;
    const load = () => {
      state.voices = speechSynthesis.getVoices();
      const sel = $("sVoice");
      sel.innerHTML = `<option value="">System default</option>` +
        state.voices.map((v) => `<option value="${escapeHtml(v.name)}">${escapeHtml(v.name)} (${escapeHtml(v.lang)})</option>`).join("");
      sel.value = state.settings.voice || "";
    };
    load();
    speechSynthesis.onvoiceschanged = load;
  }

  let speakingBtn = null;

  /** Reads text aloud. Clicking the same button again stops playback. onDone runs when speech ends. */
  function speak(text, btn, onDone) {
    if (!("speechSynthesis" in window)) { toast("Speech isn't supported in this browser", true); if (onDone) onDone(); return; }
    const wasThis = btn && speakingBtn === btn;
    speechSynthesis.cancel();
    if (speakingBtn) speakingBtn.classList.remove("on");
    speakingBtn = null;
    if (wasThis) return;
    const u = new SpeechSynthesisUtterance(cleanForSpeech(text));
    const voiceName = $("sVoice").value || state.settings.voice;
    const voice = state.voices.find((v) => v.name === voiceName);
    if (voice) u.voice = voice;
    if (btn) { btn.classList.add("on"); speakingBtn = btn; }
    const finished = () => {
      if (btn) btn.classList.remove("on");
      if (speakingBtn === btn) speakingBtn = null;
      if (onDone) onDone();
    };
    u.onend = finished;
    u.onerror = finished;
    speechSynthesis.speak(u);
  }

  /* --------------------------------------------------------------- settings */

  function openSettings(pane) {
    const s = state.settings;
    $("sDisplayName").value = state.user.displayName;
    $("sModel").value = s.model;
    $("sThinking").value = s.thinking || "fast";
    $("sTemp").value = s.temperature;
    $("tempVal").textContent = s.temperature;
    $("sPrompt").value = s.systemPrompt;
    $("sAutoSpeak").checked = s.autoSpeak === "true";
    $("sVoiceFeatures").checked = s.voiceFeatures !== "false";
    $("sVoiceReplies").checked = s.voiceReplies !== "false";
    renderVoiceOptions();
    $("sVoice").value = s.voice || "";
    $("sKey").value = "";
    $("sImgAccount").value = s.imageAccountId || "";
    $("sImgToken").value = "";
    $("settingsError").textContent = "";
    renderKeyStatus();
    renderImageStatus();
    pickTheme(s.theme);
    $("micSupport").textContent = Recognition ? "Voice input and voice chat are available in this browser." : "Voice input isn't supported in this browser. Use Chrome, Edge or Safari.";
    showPane(typeof pane === "string" ? pane : "general");
    if (!$("settingsDlg").open) $("settingsDlg").showModal();
  }

  function renderVoiceOptions() {
    $("voiceOptions").classList.toggle("pane-disabled", !$("sVoiceFeatures").checked);
  }

  function renderKeyStatus() {
    const s = state.settings;
    const el = $("keyStatus");
    if (s.hasPersonalKey) {
      el.className = "key-status ok";
      el.innerHTML = `Your Groq key is saved. <a href="#" id="removeKey">Remove it</a>`;
      $("removeKey").onclick = async (e) => {
        e.preventDefault();
        try {
          state.settings = await api("/api/settings", { method: "PUT", json: { apiKey: "" } });
          renderKeyStatus();
          renderModelPill();
        } catch (err) { $("settingsError").textContent = err.message; }
      };
    } else if (s.hasServerKey) {
      el.className = "key-status ok";
      el.textContent = "Using the key from wanda.properties. Paste a key here to use your own instead.";
    } else {
      el.className = "key-status";
      el.innerHTML = `Required for chat. Get a free key at <a href="https://console.groq.com/keys" target="_blank" rel="noopener">console.groq.com/keys</a>.`;
    }
  }

  function renderImageStatus() {
    const s = state.settings;
    const el = $("imgStatus");
    if (s.hasPersonalImageKey) {
      el.className = "key-status ok";
      el.innerHTML = `Your Cloudflare token is saved. <a href="#" id="removeImgKey">Remove it</a>`;
      $("removeImgKey").onclick = async (e) => {
        e.preventDefault();
        try {
          state.settings = await api("/api/settings", { method: "PUT", json: { imageToken: "", imageAccountId: "" } });
          $("sImgAccount").value = "";
          renderImageStatus();
        } catch (err) { $("settingsError").textContent = err.message; }
      };
    } else if (s.hasServerImageKey) {
      el.className = "key-status ok";
      el.textContent = "Using the Cloudflare key from wanda.properties. Enter your own below to use it instead.";
    } else {
      el.className = "key-status";
      el.textContent = "Not set up yet. Follow the three steps above (it's free).";
    }
  }

  function pickTheme(theme) {
    document.querySelectorAll(".theme-pick button").forEach((b) => b.classList.toggle("active", b.dataset.theme === theme));
    setTheme(theme);
  }

  function showPane(name) {
    document.querySelectorAll(".settings-nav button").forEach((b) => b.classList.toggle("active", b.dataset.pane === name));
    document.querySelectorAll(".settings-pane > div").forEach((d) => (d.hidden = d.dataset.pane !== name));
  }

  async function saveSettings() {
    const changes = {
      displayName: $("sDisplayName").value.trim(),
      theme: document.querySelector(".theme-pick .active").dataset.theme,
      model: $("sModel").value.trim(),
      thinking: $("sThinking").value,
      temperature: $("sTemp").value,
      systemPrompt: $("sPrompt").value.trim(),
      autoSpeak: String($("sAutoSpeak").checked),
      voiceFeatures: String($("sVoiceFeatures").checked),
      voiceReplies: String($("sVoiceReplies").checked),
      voice: $("sVoice").value,
      imageAccountId: $("sImgAccount").value.trim()
    };
    const key = $("sKey").value.trim();
    if (key) changes.apiKey = key;
    const imgToken = $("sImgToken").value.trim();
    if (imgToken) changes.imageToken = imgToken;
    $("settingsError").textContent = "";
    $("saveSettings").disabled = true;
    try {
      state.settings = await api("/api/settings", { method: "PUT", json: changes });
      state.user = await api("/api/auth/me");
      renderUser();
      renderModelPill();
      applyVoiceSettings();
      $("settingsDlg").close();
      toast("Settings saved");
    } catch (e) {
      $("settingsError").textContent = e.message; // shown inside the dialog, where it cannot be missed
    } finally {
      $("saveSettings").disabled = false;
    }
  }

  /* ----------------------------------------------------------------- mobile */

  function closeNav() { $("app").classList.remove("nav-open"); }

  /* --------------------------------------------------------------- bindings */

  $("composer").addEventListener("submit", (e) => { e.preventDefault(); send(); });
  $("send").addEventListener("click", (e) => {
    if (state.sending) { e.preventDefault(); if (!state.locked) state.controller.abort(); }
  });
  $("input").addEventListener("input", autosize);
  $("input").addEventListener("keydown", (e) => {
    if (e.key === "Enter" && !e.shiftKey && !e.isComposing) { e.preventDefault(); send(); }
    if (e.key === "Escape" && state.imageMode) setImageMode(false);
  });
  $("input").addEventListener("paste", (e) => {
    const files = Array.from(e.clipboardData.files || []);
    if (files.length) { e.preventDefault(); uploadFiles(files); }
  });
  const composer = $("composer");
  composer.addEventListener("dragover", (e) => { e.preventDefault(); composer.classList.add("drag"); });
  composer.addEventListener("dragleave", () => composer.classList.remove("drag"));
  composer.addEventListener("drop", (e) => {
    e.preventDefault();
    composer.classList.remove("drag");
    uploadFiles(Array.from(e.dataTransfer.files));
  });

  $("attach").onclick = () => $("fileInput").click();
  $("fileInput").onchange = (e) => { uploadFiles(Array.from(e.target.files)); e.target.value = ""; };
  $("imageMode").onclick = () => setImageMode(!state.imageMode);
  $("modeClose").onclick = () => setImageMode(false);
  $("mic").onclick = toggleMic;
  $("voiceChat").onclick = startVoiceChat;
  $("voiceEnd").onclick = stopVoiceChat;
  $("sVoiceFeatures").onchange = renderVoiceOptions;
  $("voiceMute").onclick = async () => {   // mute or unmute Wanda's voice, right in the middle of a voice chat
    const next = wandaSpeaks() ? "false" : "true";
    state.settings.voiceReplies = next;
    renderVoiceMute();
    if (next === "false" && voiceChat.speaker) voiceChat.speaker.cancel();   // stop talking now
    try { state.settings = await api("/api/settings", { method: "PUT", json: { voiceReplies: next } }); } catch (e) { toast(e.message, true); }
  };
  $("voiceOrb").onclick = () => {      // tap while Wanda speaks: stop her and talk
    if (voiceChat.on && $("voiceOverlay").dataset.state === "speaking") {
      if (voiceChat.speaker) voiceChat.speaker.cancel();
      listenOnce();
    }
  };

  document.addEventListener("keydown", (e) => {
    if (e.key === "Escape" && voiceChat.on) stopVoiceChat();
    if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === "k") {     // Ctrl/Cmd+K: jump to search
      e.preventDefault();
      $("app").classList.add("nav-open");
      $("search").focus();
      $("search").select();
    }
    if (e.key === "Escape") closeMenu();
  });
  document.addEventListener("click", (e) => { if (!e.target.closest("#convMenu") && !e.target.closest(".more")) closeMenu(); });
  $("history").addEventListener("scroll", closeMenu);
  $("convMenu").addEventListener("click", (e) => {
    const item = e.target.closest("button[data-act]");
    if (!item || !menuConv) return;
    const conv = menuConv;
    const row = document.querySelector(`.conv[data-id="${conv.id}"]`);
    closeMenu();
    if (item.dataset.act === "pin") togglePin(conv);
    else if (item.dataset.act === "rename" && row) startRename(row, conv);
    else if (item.dataset.act === "delete") deleteConversation(conv);
  });

  $("newChat").onclick = newChat;
  $("search").oninput = renderHistory;
  $("search").addEventListener("keydown", (e) => { if (e.key === "Escape") { $("search").value = ""; renderHistory(); $("search").blur(); } });
  $("menuBtn").onclick = () => $("app").classList.toggle("nav-open");
  $("scrim").onclick = closeNav;
  $("exportBtn").onclick = exportChat;
  $("tempBtn").onclick = toggleTemporary;
  window.addEventListener("pagehide", discardTemporary);

  $("themeBtn").onclick = async () => {
    const next = document.documentElement.dataset.theme === "dark" ? "light" : "dark";
    setTheme(next);
    try { state.settings = await api("/api/settings", { method: "PUT", json: { theme: next } }); } catch (e) { toast(e.message, true); }
  };

  $("logout").onclick = async () => {
    discardTemporary();
    await api("/api/auth/logout", { method: "POST" }).catch(() => {});
    window.location.href = "/login.html";
  };

  $("openSettings").onclick = () => { closeNav(); openSettings(); };
  $("saveSettings").onclick = saveSettings;
  $("settingsDlg").addEventListener("close", () => setTheme(state.settings.theme)); // undo unsaved theme preview
  document.querySelectorAll(".settings-nav button").forEach((b) => (b.onclick = () => showPane(b.dataset.pane)));
  document.querySelectorAll(".theme-pick button").forEach((b) => (b.onclick = () => pickTheme(b.dataset.theme)));
  document.querySelectorAll("[data-close]").forEach((b) => (b.onclick = () => b.closest("dialog").close()));
  $("sTemp").oninput = () => ($("tempVal").textContent = $("sTemp").value);
  $("testVoice").onclick = () => speak("Hi, I'm Wanda. This is how I sound when I read replies aloud.");
  $("clearAll").onclick = async () => {
    if (!(await confirmDialog("Delete all chats?", "Every chat in your history will be deleted for good. This can't be undone.", "Delete all"))) return;
    try {
      await api("/api/conversations", { method: "DELETE" });
      state.conversations = [];
      $("settingsDlg").close();
      newChat();
      toast("All chats deleted");
    } catch (e) { toast(e.message, true); }
  };

  init();
})();

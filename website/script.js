"use strict";

// Shared by every public page. Talks to the V-Client backend that serves this site; if the
// backend is unreachable (for example the site is opened from static hosting) the page still
// works and falls back to the bundled Windows setup file.
const FALLBACK_WINDOWS_URL = "./VSMPClientSetup.exe?v=settings-1";
const FALLBACK_ANDROID_URL = "./V-Client.apk";
const DEFAULT_JAVA_ADDRESS = "vsmp.meowlix.sh:19754";

const $ = (selector, root = document) => root.querySelector(selector);
const $$ = (selector, root = document) => [...root.querySelectorAll(selector)];

async function api(path) {
  const response = await fetch(`/api${path}`, { headers: { Accept: "application/json" } });
  if (!response.ok) throw new Error(`${path} returned ${response.status}`);
  return response.json();
}

function formatBytes(bytes) {
  if (!bytes) return "";
  return bytes > 1048576 ? `${(bytes / 1048576).toFixed(1)} MB` : `${Math.ceil(bytes / 1024)} KB`;
}

function formatDate(iso) {
  return new Date(iso).toLocaleDateString(undefined, { year: "numeric", month: "short", day: "numeric" });
}

// Builds DOM nodes with textContent only, so news and announcement text can never inject markup.
function el(tag, className, text) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text !== undefined) node.textContent = text;
  return node;
}

// ---- Navigation -------------------------------------------------------------
const menuButton = $(".menu-toggle");
const navigation = $("#site-nav");
if (menuButton && navigation) {
  menuButton.addEventListener("click", () => {
    const isOpen = menuButton.getAttribute("aria-expanded") === "true";
    menuButton.setAttribute("aria-expanded", String(!isOpen));
    navigation.classList.toggle("is-open", !isOpen);
  });
}
const here = location.pathname.split("/").pop() || "index.html";
$$(".site-nav a[href]").forEach((link) => {
  const target = link.getAttribute("href").split("#")[0];
  if (target && target === here) link.setAttribute("aria-current", "page");
});
const year = $("#current-year");
if (year) year.textContent = String(new Date().getFullYear());

// ---- Site configuration (Discord, address) ----------------------------------
api("/config").then(({ server }) => {
  $$(".discord-link").forEach((link) => {
    if (server.discordUrl) link.href = server.discordUrl;
    else link.hidden = true;
  });
  $$("[data-java-address]").forEach((node) => { node.textContent = `${server.java.host}:${server.java.port}`; });
  $$("[data-bedrock-address]").forEach((node) => { node.textContent = `${server.bedrock.host}:${server.bedrock.port}`; });
}).catch(() => {
  $$(".discord-link").forEach((link) => { link.hidden = true; });
  $$("[data-java-address]").forEach((node) => { node.textContent = DEFAULT_JAVA_ADDRESS; });
});

// ---- Download buttons -------------------------------------------------------
api("/launcher/releases").then((releases) => {
  const windows = releases.windows;
  $$("[data-download='windows']").forEach((link) => {
    link.href = windows ? "/download/windows" : FALLBACK_WINDOWS_URL;
    const version = $("[data-version]", link);
    if (version && windows) version.textContent = `v${windows.version}`;
  });
  $$("[data-download='android']").forEach((link) => {
    const android = releases.android;
    link.href = android ? "/download/android" : FALLBACK_ANDROID_URL;
    const version = $("[data-version]", link);
    if (version && android) version.textContent = `v${android.version}`;
  });
  fillReleaseCards(releases);
}).catch(() => {
  $$("[data-download='windows']").forEach((link) => { link.href = FALLBACK_WINDOWS_URL; });
  $$("[data-download='android']").forEach((link) => { link.href = FALLBACK_ANDROID_URL; });
});

function fillReleaseCards(releases) {
  for (const platform of ["windows", "android"]) {
    const release = releases[platform];
    const meta = $(`[data-release-meta='${platform}']`);
    if (!meta) continue;
    meta.textContent = release
      ? `Version ${release.version} · ${formatBytes(release.size)} · released ${formatDate(release.publishedAt)}`
      : "Bundled build (version details appear once a release is published)";
    const hash = $(`[data-release-hash='${platform}']`);
    if (hash && release && release.sha256) hash.textContent = `SHA-256 ${release.sha256}`;
    const notes = $(`[data-release-notes='${platform}']`);
    if (notes && release && release.notes) notes.textContent = release.notes;
  }
}

// ---- Announcement banner ----------------------------------------------------
const banner = $("#announcement-banner");
if (banner) {
  api("/announcements").then(({ items }) => {
    if (!items.length) return;
    banner.replaceChildren(...items.slice(0, 3).map((item) => el("p", `announcement ${item.level}`, item.message)));
    banner.hidden = false;
  }).catch(() => {});
}

// ---- Server status ----------------------------------------------------------
function renderServerStatus(root, status) {
  const java = status.java;
  const bedrock = status.bedrock;
  const players = java.online ? java.players : bedrock.online ? bedrock.players : null;
  const online = java.online || bedrock.online;
  root.classList.toggle("is-online", online);
  root.classList.toggle("is-offline", !online);
  const state = $("[data-status-state]", root);
  if (state) state.textContent = online ? "Online" : "Offline";
  const count = $("[data-status-players]", root);
  if (count) count.textContent = players ? `${players.online} / ${players.max}` : "—";
  const detail = $("[data-status-detail]", root);
  if (detail) {
    detail.textContent = online
      ? [java.online ? `Java ${java.version}` : null, bedrock.online ? "Bedrock online" : null].filter(Boolean).join(" · ")
      : "The server did not answer. Try again in a moment.";
  }
}

$$("[data-server-status]").forEach((root) => {
  const refresh = () => api("/server/status").then((status) => renderServerStatus(root, status)).catch(() => {
    const state = $("[data-status-state]", root);
    if (state) state.textContent = "Unavailable";
  });
  refresh();
  setInterval(refresh, 30000);
});

// ---- Copy to clipboard ------------------------------------------------------
$$("[data-copy]").forEach((button) => {
  button.addEventListener("click", async () => {
    const source = $(button.dataset.copy);
    if (!source) return;
    try {
      await navigator.clipboard.writeText(source.textContent.trim());
      const original = button.textContent;
      button.textContent = "Copied";
      setTimeout(() => { button.textContent = original; }, 1500);
    } catch {
      button.textContent = "Copy failed";
    }
  });
});

// ---- News -------------------------------------------------------------------
function newsCard(post) {
  const card = el("article", "news-card");
  const meta = el("div", "news-meta");
  meta.append(el("span", `news-tag ${post.category}`, post.category === "server" ? "Server" : "Launcher"));
  if (post.pinned) meta.append(el("span", "news-tag pinned", "Pinned"));
  meta.append(el("time", "", formatDate(post.createdAt)));
  card.append(meta, el("h3", "", post.title));
  for (const paragraph of post.body.split(/\n{2,}/)) card.append(el("p", "", paragraph));
  return card;
}

const newsList = $("#news-list");
if (newsList) {
  const limit = Number(newsList.dataset.limit || 20);
  const filters = $$("[data-news-filter]");
  const load = (category) => {
    const query = `?limit=${limit}${category ? `&category=${category}` : ""}`;
    api(`/news${query}`).then(({ items }) => {
      newsList.replaceChildren(...(items.length ? items.map(newsCard) : [el("p", "empty-state", "No posts yet. Check back soon.")]));
    }).catch(() => newsList.replaceChildren(el("p", "empty-state", "News could not be loaded right now.")));
  };
  filters.forEach((button) => button.addEventListener("click", () => {
    filters.forEach((other) => other.setAttribute("aria-pressed", String(other === button)));
    load(button.dataset.newsFilter);
  }));
  load("");
}

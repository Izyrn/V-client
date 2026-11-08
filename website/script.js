"use strict";

// Shared by every public page. The API (news, announcements, server status) is optional: if it cannot be reached the page still works.
const GITHUB_REPO = "eliteprofast/V-client";
const WINDOWS_RELEASE_URL = `https://github.com/${GITHUB_REPO}/releases/latest/download/VSMPClientSetup.exe`;
// The V-Client API (Cloudflare Worker). Change this one line if the address changes.
const API_BASE = "https://vclient-api.vclient-api.workers.dev";
const ANDROID_URL = "./V-Client.apk";
const DEFAULT_JAVA_ADDRESS = "vsmp.meowlix.sh:19754";

const $ = (selector, root = document) => root.querySelector(selector);
const $$ = (selector, root = document) => [...root.querySelectorAll(selector)];

async function api(path) {
  const response = await fetch(`${API_BASE}/api${path}`, { headers: { Accept: "application/json" } });
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
  $$("[data-bedrock-address]").forEach((node) => { node.textContent = server.bedrock.port ? `${server.bedrock.host}:${server.bedrock.port}` : "Not available"; });
}).catch(() => {
  $$(".discord-link").forEach((link) => { link.hidden = true; });
  $$("[data-java-address]").forEach((node) => { node.textContent = DEFAULT_JAVA_ADDRESS; });
});

// ---- Download buttons -------------------------------------------------------
// The installer is a GitHub Release asset, so downloads are counted by GitHub and need no backend.
// Version details come from update.json, which is published together with the site.
$$("[data-download='windows']").forEach((link) => { link.href = WINDOWS_RELEASE_URL; });
$$("[data-download='android']").forEach((link) => { link.href = ANDROID_URL; });

fetch("./update.json", { cache: "no-cache" }).then((r) => (r.ok ? r.json() : Promise.reject(new Error("update.json")))).then((info) => {
  $$("[data-download='windows'] [data-version]").forEach((node) => { node.textContent = `v${info.version}`; });
  fillReleaseCards({
    windows: { version: info.version, size: info.size, sha256: info.sha256, notes: info.notes, publishedAt: info.publishedAt },
    android: null,
  });
}).catch(() => fillReleaseCards({ windows: null, android: null }));

// Track every click on a download button (GoatCounter event "download-click"; harmless if the script is blocked).
$$("[data-download]").forEach((link) => {
  link.addEventListener("click", () => {
    try {
      if (window.goatcounter && window.goatcounter.count) {
        window.goatcounter.count({ path: `download-click-${link.dataset.download}`, title: "download-click", event: true });
      }
    } catch { /* tracking must never block a download */ }
  });
});

// ---- Download counter (sum of every release asset on GitHub, cached for 10 minutes) ----
const COUNT_CACHE_KEY = "vclient-download-count";
const COUNT_CACHE_MS = 10 * 60 * 1000;

function showDownloadCount(total) {
  $$("[data-download-count]").forEach((node) => {
    node.textContent = `${total.toLocaleString()} downloads`;
    node.hidden = false;
  });
}

async function loadDownloadCount() {
  try {
    const cached = JSON.parse(localStorage.getItem(COUNT_CACHE_KEY) || "null");
    if (cached && Date.now() - cached.at < COUNT_CACHE_MS) {
      showDownloadCount(cached.total);
      return;
    }
  } catch { /* storage unavailable: just fetch */ }
  try {
    const response = await fetch(`https://api.github.com/repos/${GITHUB_REPO}/releases?per_page=100`, {
      headers: { Accept: "application/vnd.github+json" },
    });
    if (!response.ok) return; // rate limited or offline: stay silent, the counter stays hidden
    const releases = await response.json();
    const total = releases.reduce((sum, release) => sum + (release.assets || []).reduce((n, asset) => n + (asset.download_count || 0), 0), 0);
    showDownloadCount(total);
    try { localStorage.setItem(COUNT_CACHE_KEY, JSON.stringify({ at: Date.now(), total })); } catch { /* ignore */ }
  } catch { /* hide silently */ }
}
if ($("[data-download-count]")) loadDownloadCount();

function fillReleaseCards(releases) {
  for (const platform of ["windows", "android"]) {
    const release = releases[platform];
    const meta = $(`[data-release-meta='${platform}']`);
    if (!meta) continue;
    meta.textContent = release
      ? `Version ${release.version} · ${formatBytes(release.size)} · released ${formatDate(release.publishedAt)}`
      : platform === "android" ? "Bundled build" : "Latest release on GitHub";
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

// ==UserScript==
// @name         AshportPDA Visual Toast
// @match        https://ashport.net/*
// @run-at       document-end
// ==/UserScript==

const launches = (GM_getValue("launch_count", 0) || 0) + 1;
GM_setValue("launch_count", launches);

const toast = document.createElement("div");
toast.textContent = "AshportPDA Active (Run #" + launches + ")";
toast.style.cssText = "position:fixed;bottom:16px;right:16px;background:#111;color:#0f0;padding:8px 12px;border-radius:6px;border:1px solid #0f0;z-index:999999;font-family:monospace;font-size:12px;pointer-events:none;transition:opacity 0.5s;";
document.body.appendChild(toast);
setTimeout(() => { toast.style.opacity = "0"; setTimeout(() => toast.remove(), 500); }, 3000);

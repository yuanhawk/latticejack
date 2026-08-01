// Plain vanilla JS - no framework, no custom ANSI terminal emulation. A
// <pre> log pane is the honest, correct choice here: this page shows raw
// output from real scripts, not a stylized simulation of a terminal.

(() => {
  "use strict";

  const els = {
    badge: document.getElementById("vm-badge"),
    badgeText: document.getElementById("vm-badge-text"),
    landing: document.getElementById("landing"),
    run: document.getElementById("run"),
    startButton: document.getElementById("start-button"),
    startError: document.getElementById("start-error"),
    runSession: document.getElementById("run-session"),
    stageTimeline: document.getElementById("stage-timeline"),
    verdictPanel: document.getElementById("verdict-panel"),
    verdictList: document.getElementById("verdict-list"),
    logPane: document.getElementById("log-pane"),
    endState: document.getElementById("end-state"),
    endStateTitle: document.getElementById("end-state-title"),
    endStateDetail: document.getElementById("end-state-detail"),
    endStateNumbers: document.getElementById("end-state-numbers"),
  };

  const STATUS_POLL_MS = 10_000;
  const LOG_POLL_MS = 2_000;

  let logOffset = 0;
  let renderedVerdictCount = 0;
  let logPollTimer = null;
  let terminal = false; // true once state is done/failed and we've rendered the end state

  // --- VM status badge -------------------------------------------------

  async function pollStatus() {
    try {
      const res = await fetch("/api/status");
      if (!res.ok) throw new Error(`status ${res.status}`);
      const data = await res.json();
      renderBadge(data);
    } catch (err) {
      els.badge.className = "badge badge-error";
      els.badgeText.textContent = "status unavailable";
      console.warn("status poll failed", err);
    }
  }

  function renderBadge(data) {
    const power = data.vmPowerState || "PowerState/unknown";
    if (data.active) {
      els.badge.className = "badge badge-running";
      els.badgeText.textContent = "run in progress";
    } else if (power === "PowerState/deallocated") {
      els.badge.className = "badge badge-deallocated";
      els.badgeText.textContent = "VM deallocated (idle)";
    } else if (power === "PowerState/running") {
      els.badge.className = "badge badge-running";
      els.badgeText.textContent = "VM running";
    } else {
      els.badge.className = "badge badge-unknown";
      els.badgeText.textContent = power.replace("PowerState/", "") || "unknown";
    }
  }

  // --- Turnstile ---------------------------------------------------------

  function renderTurnstile() {
    const siteKey = (window.LATTICEJACK_DEMO_CONFIG || {}).turnstileSiteKey;
    if (!siteKey || siteKey.startsWith("<<FILL_IN")) {
      els.startError.hidden = false;
      els.startError.textContent =
        "Turnstile site key not configured yet (owner TODO in index.html) - Start is disabled.";
      return;
    }
    const container = document.getElementById("turnstile-container");
    const attempt = () => {
      if (window.turnstile) {
        window.turnstile.render(container, {
          sitekey: siteKey,
          callback: () => {
            els.startButton.disabled = false;
          },
          "expired-callback": () => {
            els.startButton.disabled = true;
          },
        });
      } else {
        setTimeout(attempt, 200);
      }
    };
    attempt();
  }

  // --- Start flow ----------------------------------------------------------

  async function startDemo() {
    els.startButton.disabled = true;
    els.startError.hidden = true;

    const token = window.turnstile ? window.turnstile.getResponse() : "";
    try {
      const res = await fetch("/api/start", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ turnstileToken: token }),
      });
      const data = await res.json();

      if (!res.ok) {
        showStartError(data);
        els.startButton.disabled = false;
        return;
      }

      enterRunView(data.sessionId);
    } catch (err) {
      els.startError.hidden = false;
      els.startError.textContent = "Could not reach the Worker - network error. " + err;
      els.startButton.disabled = false;
    }
  }

  function showStartError(data) {
    els.startError.hidden = false;
    if (data.error === "daily_cap_reached") {
      els.startError.textContent = `Daily demo-run cap (${data.cap}) reached for today - please try again tomorrow.`;
    } else if (data.error === "rate_limited") {
      els.startError.textContent = `You've started this demo a few times in the last hour (limit ${data.cap}/hour) - please wait a bit.`;
    } else if (data.error === "turnstile_failed") {
      els.startError.textContent = "Turnstile verification failed - reload the page and try again.";
    } else {
      els.startError.textContent = data.message || `Could not start the demo (${data.error || "unknown error"}).`;
    }
  }

  // --- Run view ------------------------------------------------------------

  function enterRunView(sessionId) {
    els.landing.hidden = true;
    els.run.hidden = false;
    els.runSession.textContent = `Session ${sessionId}`;
    logOffset = 0;
    renderedVerdictCount = 0;
    terminal = false;
    els.logPane.textContent = "";
    els.endState.hidden = true;
    pollLog(sessionId);
  }

  async function pollLog(sessionId) {
    if (logPollTimer) clearTimeout(logPollTimer);
    try {
      const res = await fetch(`/api/log?session=${encodeURIComponent(sessionId)}&after=${logOffset}`);
      if (res.status === 404) {
        renderTerminal("failed", "Session not found (it may have expired or a new run started).", null);
        return;
      }
      if (!res.ok) throw new Error(`log poll ${res.status}`);
      const data = await res.json();

      if (data.log) {
        els.logPane.textContent += data.log;
        els.logPane.scrollTop = els.logPane.scrollHeight;
      }
      logOffset = data.nextOffset;

      renderStages(data.stages);
      renderVerdicts(data.verdicts || []);

      if (data.state === "done" || data.state === "failed") {
        renderTerminal(data.state, data.failureReason, data.doneSummary);
        return; // stop polling once terminal
      }
    } catch (err) {
      console.warn("log poll failed, will retry", err);
    }
    logPollTimer = setTimeout(() => pollLog(sessionId), LOG_POLL_MS);
  }

  function renderStages(stages) {
    if (!stages) return;
    for (const li of els.stageTimeline.querySelectorAll("li[data-stage]")) {
      const stage = li.getAttribute("data-stage");
      const s = stages[stage];
      if (s) li.setAttribute("data-status", s.status);
    }
  }

  function renderVerdicts(verdicts) {
    if (verdicts.length <= renderedVerdictCount) return;
    els.verdictPanel.hidden = false;
    for (let i = renderedVerdictCount; i < verdicts.length; i++) {
      const li = document.createElement("li");
      li.textContent = verdicts[i]; // literal text only, never paraphrased
      els.verdictList.appendChild(li);
    }
    renderedVerdictCount = verdicts.length;
  }

  function renderTerminal(state, failureReason, doneSummary) {
    if (terminal) return;
    terminal = true;
    els.endState.hidden = false;
    els.endState.className = `end-state state-${state}`;

    if (state === "done") {
      els.endStateTitle.textContent = "Run complete - VM deallocation confirmed";
      els.endStateDetail.textContent =
        "The demo finished and the Azure VM has been confirmed deallocated.";
      if (doneSummary && (doneSummary.handshake_ms || doneSummary.ratio)) {
        els.endStateNumbers.hidden = false;
        els.endStateNumbers.innerHTML = "";
        addNumber("Handshake time", fmtMs(doneSummary.handshake_ms));
        addNumber("Request-to-response", fmtMs(doneSummary.request_to_response_ms));
        addNumber("Handshake / inference ratio", doneSummary.ratio != null ? doneSummary.ratio.toFixed(3) : "n/a");
      }
    } else {
      els.endStateTitle.textContent = "Run failed";
      els.endStateDetail.textContent =
        failureReason || "The run failed for an unspecified reason - see the raw output above.";
    }
  }

  function addNumber(label, value) {
    const dt = document.createElement("dt");
    dt.textContent = label;
    const dd = document.createElement("dd");
    dd.textContent = value;
    els.endStateNumbers.appendChild(dt);
    els.endStateNumbers.appendChild(dd);
  }

  function fmtMs(v) {
    return v == null ? "n/a" : `${Number(v).toFixed(1)} ms`;
  }

  // --- wire up -------------------------------------------------------------

  els.startButton.addEventListener("click", startDemo);
  renderTurnstile();
  pollStatus();
  setInterval(pollStatus, STATUS_POLL_MS);

  // If we land on the page mid-run (e.g. someone else's session, or a
  // refresh), /api/status tells us active=true + sessionId - hop straight
  // into spectator/run view instead of showing the landing page.
  (async () => {
    try {
      const res = await fetch("/api/status");
      const data = await res.json();
      if (data.active && data.sessionId) {
        enterRunView(data.sessionId);
      }
    } catch {
      // Non-fatal - user can still click Start once Turnstile loads.
    }
  })();
})();

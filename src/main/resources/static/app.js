const $ = (id) => document.getElementById(id);
const state = { jobs: [], filter: "ALL", selectedId: null, loading: false };
const statusNames = { QUEUED: "Queued", RUNNING: "Running", SUCCEEDED: "Succeeded", DEAD_LETTERED: "Dead lettered" };

function node(tag, className, text) {
  const element = document.createElement(tag);
  if (className) element.className = className;
  if (text !== undefined && text !== null) element.textContent = String(text);
  return element;
}

function shortId(id) { return id ? id.slice(0, 8) + "…" : "—"; }
function time(value) {
  if (!value) return "—";
  return new Intl.DateTimeFormat(undefined, { month: "short", day: "numeric", hour: "numeric", minute: "2-digit" }).format(new Date(value));
}
function seconds(value) {
  if (!Number.isFinite(value)) return "—";
  return value < 60 ? `${Math.max(0, value).toFixed(1)}s` : `${(value / 60).toFixed(1)}m`;
}
function setText(id, value) { $(id).textContent = String(value); }

async function api(path, options = {}) {
  const response = await fetch(path, { cache: "no-store", ...options });
  if (!response.ok) {
    const body = await response.text();
    const html = (response.headers.get("content-type") || "").includes("text/html");
    throw new Error(response.status === 401 ? "Authentication is required. Reload the page and sign in." : html ? `Queue API unavailable (${response.status}).` : body || `Request failed (${response.status}).`);
  }
  return response.json();
}

function renderMetrics(metrics) {
  const counts = metrics.statusCounts || {};
  setText("queued-count", counts.QUEUED ?? 0);
  setText("running-count", counts.RUNNING ?? 0);
  setText("succeeded-count", counts.SUCCEEDED ?? 0);
  setText("dead-count", counts.DEAD_LETTERED ?? 0);
  setText("queue-age", seconds(metrics.oldestReadyJobWaitSeconds));
  setText("expired-leases", metrics.expiredLeases ?? 0);
  setText("stale-rejections", metrics.staleCompletionRejections ?? 0);
}

function renderJobs() {
  const body = $("jobs-body");
  body.replaceChildren();
  const visible = state.filter === "ALL" ? state.jobs : state.jobs.filter((job) => job.status === state.filter);
  setText("job-count", `${visible.length} shown`);
  if (!visible.length) {
    const row = node("tr");
    const cell = node("td", "empty-cell", state.jobs.length ? "No jobs match this filter." : "No jobs yet. Submit one to see it here.");
    cell.colSpan = 6;
    row.append(cell);
    body.append(row);
    return;
  }
  for (const job of visible) {
    const row = node("tr");
    const identity = node("td");
    const id = node("span", "job-id", shortId(job.jobId));
    id.title = job.jobId;
    identity.append(id, node("span", "job-type", job.jobType));
    const status = node("td");
    status.append(node("span", `badge ${job.status}`, statusNames[job.status] || job.status));
    const attempt = node("td", "", `${job.attemptCount} / ${job.maxAttempts}`);
    const owner = node("td", job.workerId ? "owner" : "muted", job.workerId || "Unassigned");
    const created = node("td", "", time(job.createdAt));
    const action = node("td");
    const button = node("button", "detail-link", "Inspect ↗");
    button.type = "button";
    button.setAttribute("aria-label", `Inspect job ${job.jobId}`);
    button.addEventListener("click", () => openDrawer(job.jobId));
    action.append(button);
    row.append(identity, status, attempt, owner, created, action);
    body.append(row);
  }
}

function detail(list, label, value) {
  const item = node("div", "detail-item");
  item.append(node("dt", "", label), node("dd", "", value ?? "—"));
  list.append(item);
}

function explanation(job) {
  if (job.status === "RUNNING") return "This worker may update the job only while its lease is valid and its ownership generation is current.";
  if (job.status === "QUEUED" && job.attemptCount > 0) return "A previous attempt failed or timed out. This job will be eligible again at its scheduled time.";
  if (job.status === "QUEUED") return "This job is waiting for an available worker to claim it.";
  if (job.status === "DEAD_LETTERED") return "The retry limit was reached. Review the last error before requeueing.";
  return "The current owner completed this job successfully.";
}

function renderDrawer(job) {
  const content = $("drawer-content");
  content.replaceChildren();
  content.append(node("h2", "drawer-title", job.jobType.replaceAll("_", " ").toLowerCase()), node("div", "drawer-id", job.jobId));
  const status = node("div", "drawer-status");
  status.append(node("span", `badge ${job.status}`, statusNames[job.status] || job.status));
  content.append(status);
  const details = node("dl", "detail-grid");
  detail(details, "Failed attempts", `${job.attemptCount} of ${job.maxAttempts}`);
  detail(details, "Worker", job.workerId || "Unassigned");
  detail(details, "Ownership generation", job.leaseGeneration || "—");
  detail(details, "Lease expires", time(job.leaseExpiresAt));
  detail(details, "Next eligible", job.status === "QUEUED" ? time(job.nextRunAt) : "—");
  detail(details, "Created", time(job.createdAt));
  detail(details, "Last updated", time(job.updatedAt));
  content.append(details, node("p", "drawer-note", explanation(job)));
  if (job.lastError) content.append(node("div", "drawer-error", `Last error: ${job.lastError}`));
  if (job.status === "DEAD_LETTERED") {
    const button = node("button", "requeue-button", "Requeue this job");
    button.type = "button";
    button.addEventListener("click", async () => {
      button.disabled = true;
      try {
        await api(`/jobs/${job.jobId}/requeue`, { method: "POST" });
        await refresh();
      } catch (error) {
        showError(error.message);
        button.disabled = false;
      }
    });
    content.append(button);
  }
}

function openDrawer(id, fallbackJob) {
  state.selectedId = id;
  const job = state.jobs.find((item) => item.jobId === id) || fallbackJob;
  if (job) renderDrawer(job);
  $("drawer-backdrop").hidden = false;
  $("job-drawer").classList.add("open");
  $("job-drawer").setAttribute("aria-hidden", "false");
  $("drawer-close").focus();
}

function closeDrawer() {
  state.selectedId = null;
  $("job-drawer").classList.remove("open");
  $("job-drawer").setAttribute("aria-hidden", "true");
  $("drawer-backdrop").hidden = true;
}

function showError(message) {
  const banner = $("error-banner");
  banner.textContent = message;
  banner.hidden = false;
  if (!state.jobs.length) {
    const cell = $("jobs-body").querySelector(".empty-cell");
    if (cell) cell.textContent = "Queue data is unavailable.";
  }
  $("connection").classList.add("error");
  $("connection").lastChild.textContent = " Offline";
  $("rail-dot").classList.add("error");
}

async function refresh() {
  if (state.loading) return;
  state.loading = true;
  try {
    const [metrics, jobs] = await Promise.all([api("/metrics"), api("/jobs?limit=30")]);
    state.jobs = jobs;
    renderMetrics(metrics);
    renderJobs();
    if (state.selectedId) {
      const selected = jobs.find((job) => job.jobId === state.selectedId);
      if (selected) renderDrawer(selected);
    }
    $("error-banner").hidden = true;
    $("connection").classList.remove("error");
    $("connection").lastChild.textContent = " Connected";
    $("rail-dot").classList.remove("error");
    setText("last-updated", `Updated ${new Intl.DateTimeFormat(undefined, { hour: "numeric", minute: "2-digit", second: "2-digit" }).format(new Date())}`);
  } catch (error) {
    showError(error.message || "Could not load the queue.");
  } finally {
    state.loading = false;
  }
}

async function submitJob(event) {
  event.preventDefault();
  const button = $("submit-button");
  const message = $("form-message");
  button.disabled = true;
  message.textContent = "Submitting…";
  message.classList.remove("error");
  try {
    const jobType = $("job-type").value;
    const payload = jobType === "SLEEP_JOB"
      ? { sleepSeconds: Number($("sleep-seconds").value) }
      : { message: $("fail-message").value.trim() || "Demo failure" };
    const key = $("idempotency-key").value.trim();
    const job = await createJob(jobType, payload, Number($("max-attempts").value), key);
    message.textContent = `Job ${shortId(job.jobId)} submitted.`;
    await refresh();
    openDrawer(job.jobId, job);
  } catch (error) {
    message.textContent = error.message || "Submission failed.";
    message.classList.add("error");
  } finally {
    button.disabled = false;
  }
}

function createJob(jobType, payload, maxAttempts, key = "") {
  const headers = { "Content-Type": "application/json" };
  if (key) headers["Idempotency-Key"] = key;
  return api("/jobs", { method: "POST", headers, body: JSON.stringify({ jobType, payload: JSON.stringify(payload), maxAttempts }) });
}

async function runDemo(button) {
  const success = button.dataset.demo === "success";
  const message = $("demo-message");
  button.disabled = true;
  message.classList.remove("error");
  message.textContent = "Creating a live demo job...";
  try {
    const job = success
      ? await createJob("SLEEP_JOB", { sleepSeconds: 1 }, 1)
      : await createJob("FAIL_JOB", { message: "Intentional demo failure" }, 2);
    state.filter = "ALL";
    document.querySelectorAll(".filter").forEach((item) => item.classList.toggle("active", item.dataset.filter === "ALL"));
    await refresh();
    openDrawer(job.jobId, job);
    message.textContent = success
      ? `Created ${shortId(job.jobId)}. Watch it reach Succeeded in the inspector.`
      : `Created ${shortId(job.jobId)}. Watch its scheduled retry, then Dead lettered.`;
  } catch (error) {
    message.textContent = error.message || "Could not create the demo job.";
    message.classList.add("error");
  } finally {
    button.disabled = false;
  }
}

$("refresh-button").addEventListener("click", refresh);
$("job-form").addEventListener("submit", submitJob);
document.querySelectorAll(".demo-button").forEach((button) => button.addEventListener("click", () => runDemo(button)));
$("job-type").addEventListener("change", () => {
  const sleep = $("job-type").value === "SLEEP_JOB";
  $("sleep-field").hidden = !sleep;
  $("fail-field").hidden = sleep;
  $("sleep-seconds").required = sleep;
});
document.querySelectorAll(".filter").forEach((button) => button.addEventListener("click", () => {
  state.filter = button.dataset.filter;
  document.querySelectorAll(".filter").forEach((item) => item.classList.toggle("active", item === button));
  renderJobs();
}));
$("drawer-close").addEventListener("click", closeDrawer);
$("drawer-backdrop").addEventListener("click", closeDrawer);
document.addEventListener("keydown", (event) => { if (event.key === "Escape") closeDrawer(); });
refresh();
setInterval(refresh, 3000);

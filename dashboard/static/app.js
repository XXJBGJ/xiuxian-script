// 修仙脚本工具 前端逻辑
const $ = (sel) => document.querySelector(sel);

// ---------- 标签切换
document.querySelectorAll(".tab").forEach((btn) => {
  btn.addEventListener("click", () => {
    document.querySelectorAll(".tab").forEach((b) => b.classList.remove("active"));
    document.querySelectorAll(".panel").forEach((p) => p.classList.remove("active"));
    btn.classList.add("active");
    $("#panel-" + btn.dataset.tab).classList.add("active");
  });
});

// ---------- GitHub 信息
async function loadGithub() {
  try {
    const r = await fetch("/api/github");
    const data = await r.json();
    const g = data.github || {};
    const link = $("#github-link");
    link.href = g.url || "#";
    $("#repo-name").textContent = g.owner ? `${g.owner}/${g.repo}` : "GitHub 仓库";
  } catch (e) {
    $("#repo-name").textContent = "GitHub 未接通";
  }
}

// ---------- 分步配置
let scripts = [];
let selectedFile = null;
let pollTimer = null;

async function loadScripts() {
  try {
    const r = await fetch("/api/scripts");
    const data = await r.json();
    scripts = data.scripts || [];
    renderScriptList();
  } catch (e) {
    $("#script-list").innerHTML = '<div class="empty-hint">配置目录读取失败</div>';
  }
}

function renderScriptList() {
  const box = $("#script-list");
  if (!scripts.length) {
    box.innerHTML = '<div class="empty-hint">暂无分步配置</div>';
    return;
  }
  box.innerHTML = "";
  scripts.forEach((s) => {
    const item = document.createElement("div");
    item.className = "script-item" + (s.file === selectedFile ? " active" : "");
    item.innerHTML = `
      <div class="si-title">${escapeHtml(s.name || s.file)}</div>
      <div class="si-meta">${s.steps} 步 · 缺模板 ${(s.pending_templates || []).length} 个</div>`;
    item.addEventListener("click", () => selectScript(s));
    box.appendChild(item);
  });
}

function selectScript(s) {
  selectedFile = s.file;
  renderScriptList();
  $("#script-detail-empty").hidden = true;
  $("#script-detail-body").hidden = false;
  $("#detail-name").textContent = s.name || s.file;
  $("#detail-steps").textContent = `${s.steps} 步`;
  const pending = s.pending_templates || [];
  $("#detail-pending").textContent = pending.length ? `缺模板 ${pending.length} 个` : "模板齐全";
  $("#detail-note").textContent = s.note || "";
}

$("#btn-run")?.addEventListener("click", async () => {
  if (!selectedFile) return;
  const loops = parseInt($("#opt-loops").value || "1", 10);
  const speed = parseFloat($("#opt-speed").value || "1");
  const probe = null;
  const shot = $("#opt-shot").checked;
  $("#run-log").textContent = "正在拉起 runner …\n";
  $("#btn-run").disabled = true;
  try {
    const r = await fetch("/api/run", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ config: selectedFile, loops, speed, probe, shot }),
    });
    const data = await r.json();
    if (data.error) {
      $("#run-log").textContent += "错误: " + data.error + "\n";
      $("#btn-run").disabled = false;
      return;
    }
    pollJob(data.job_id);
  } catch (e) {
    $("#run-log").textContent += "启动失败: " + e + "\n";
    $("#btn-run").disabled = false;
  }
});

function pollJob(jobId) {
  if (pollTimer) clearInterval(pollTimer);
  pollTimer = setInterval(async () => {
    try {
      const r = await fetch("/api/run/" + jobId);
      const job = await r.json();
      $("#run-log").textContent = (job.log || []).join("\n");
      const box = $("#run-log");
      box.scrollTop = box.scrollHeight;
      if (job.status === "finished" || job.status === "error") {
        clearInterval(pollTimer);
        $("#btn-run").disabled = false;
      }
    } catch (e) {
      clearInterval(pollTimer);
      $("#btn-run").disabled = false;
    }
  }, 800);
}

// ---------- Tabbit 浏览器任务
async function loadTabbitTasks() {
  const box = $("#tabbit-tasks");
  try {
    const r = await fetch("/api/tasks");
    const data = await r.json();
    const tasks = data.tasks || [];
    if (!tasks.length) {
      box.innerHTML = '<li class="task-title">未读取到任务项</li>';
      return;
    }
    box.innerHTML = "";
    tasks.slice(0, 50).forEach((t) => {
      const li = document.createElement("li");
      li.innerHTML = `<div><div class="task-title">${escapeHtml(t.title)}</div></div><span class="status pending">${t.hits} 命中</span>`;
      box.appendChild(li);
    });
  } catch (e) {
    box.innerHTML = '<li class="task-title">Tabbit 任务读取失败</li>';
  }
}

// ---------- 项目任务
async function loadProjectTasks() {
  const box = $("#project-tasks");
  try {
    const r = await fetch("/api/project-tasks");
    const data = await r.json();
    const tasks = data.tasks || [];
    if (!tasks.length) {
      box.innerHTML = '<li class="task-title">暂无项目任务</li>';
      return;
    }
    box.innerHTML = "";
    tasks.forEach((t) => {
      const li = document.createElement("li");
      li.innerHTML = `
        <div>
          <div class="task-title">${escapeHtml(t.title)}</div>
          <div class="task-note">${escapeHtml(t.note || "")}</div>
        </div>
        <span class="status ${t.status}">${statusText(t.status)}</span>`;
      box.appendChild(li);
    });
  } catch (e) {
    box.innerHTML = '<li class="task-title">项目任务读取失败</li>';
  }
}

function statusText(s) {
  return { done: "已闭环", pending: "待闭环", in_progress: "进行中" }[s] || s;
}

// ---------- 安卓端操作按钮（电脑端暂占位）
document.querySelectorAll(".action-btn").forEach((btn) => {
  btn.addEventListener("click", () => {
    alert("该功能属于安卓端（" + btn.dataset.action + "），电脑工作台暂未接入，后续在对应端实现。");
  });
});

// ---------- 工具
function escapeHtml(str) {
  return String(str || "").replace(/[&<>"']/g, (c) => ({
    "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;",
  }[c]));
}

// ---------- 设备状态
async function loadStatus() {
  try {
    const r = await fetch("/api/status");
    const s = await r.json();
    $("#st-accessibility").textContent = s.accessibility;
    $("#st-script").textContent = s.script;
    $("#st-screen").textContent = s.screen;
    $("#st-account").textContent = s.account;
    $("#st-overlay").textContent = s.overlay;
    $("#st-android").textContent = s.android;
    $("#st-logpath").textContent = s.log_path;
    $("#st-game").textContent = s.game;
    $("#st-gamever").textContent = s.game_version;
  } catch (e) {
    // 保留默认占位
  }
}

// ---------- 初始化
loadStatus();
loadGithub();
loadScripts();
loadTabbitTasks();
loadProjectTasks();

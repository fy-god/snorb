const fs = require("fs");
const path = require("path");

const repoRoot = path.resolve(__dirname, "..");
const input = process.argv[2] || "popular20_connectivity_20260521_001.json";
const inputPath = path.resolve(repoRoot, input);
const outDir = path.join(repoRoot, "docs");
const outJson = path.join(outDir, "POPULAR_20_APP_BATCH_VERIFICATION_20260521.json");
const outMd = path.join(outDir, "POPULAR_20_APP_BATCH_VERIFICATION_20260521.md");

if (!fs.existsSync(inputPath)) {
  console.error(`Missing input report: ${inputPath}`);
  process.exit(2);
}

const sourceReport = readJson(inputPath);
const batch = sourceReport.payload || sourceReport;
const manifests = loadEvidenceManifests();
const pathMap = buildDevicePathMap(manifests);
const rows = [];

for (const app of batch.apps || []) {
  for (const task of app.tasks || []) {
    rows.push(verifyTask(app, task));
  }
}

const summary = {
  sourceReport: inputPath,
  phoneStatus: batch.status || sourceReport.status || "UNKNOWN",
  appCount: batch.summary?.appCount || (batch.apps || []).length,
  taskCount: batch.summary?.taskCount || rows.length,
  executedAttempts: batch.summary?.executedAttempts || 0,
  phoneSuccess: batch.summary?.success || 0,
  phoneFailed: batch.summary?.failed || 0,
  phoneBlocked: batch.summary?.blocked || 0,
  computerPass: rows.filter((r) => r.computerVerdict === "PASS").length,
  computerFail: rows.filter((r) => r.computerVerdict === "FAIL").length,
  computerUnknown: rows.filter((r) => r.computerVerdict === "UNKNOWN").length,
  aiJobs: rows.reduce((sum, r) => sum + r.ai.jobCount, 0),
  aiSuccess: rows.reduce((sum, r) => sum + r.ai.successCount, 0),
  aiFailed: rows.reduce((sum, r) => sum + r.ai.failedCount, 0),
  aiHttp402: rows.reduce((sum, r) => sum + r.ai.http402Count, 0),
  localPngs: rows.reduce((sum, r) => sum + r.evidence.localPngCount, 0),
  pulledEvidenceFiles: manifests.reduce((sum, m) => sum + (m.pulledCount || 0), 0),
  failedEvidenceFiles: manifests.reduce((sum, m) => sum + (m.failedCount || 0), 0),
};

const output = {
  schemaVersion: 1,
  kind: "popular_20_app_batch_computer_verification",
  generatedAt: new Date().toISOString(),
  policy: {
    adbManualTargetUiControl: false,
    phoneSideSuccessIsNotEnough: true,
    passRequiresGoalOrStrongUiEvidence: true,
    highRiskPaymentTradeIsBlocked: true,
  },
  summary,
  rows,
};

fs.mkdirSync(outDir, { recursive: true });
fs.writeFileSync(outJson, `${JSON.stringify(output, null, 2)}\n`, "utf8");
fs.writeFileSync(outMd, renderMarkdown(output), "utf8");

console.log(JSON.stringify({ ok: true, summary, outJson, outMd }, null, 2));

function verifyTask(app, task) {
  const flow = task.flowReport || {};
  const flowFile = task.flowReportFile || flow.reportFile || "";
  const text = JSON.stringify(task);
  const status = String(task.status || "");
  const evidence = summarizeEvidence(task);
  const ai = summarizeAi(task);
  const artifacts = summarizeArtifacts(task);
  const blockers = [];
  const reasons = [];
  let computerVerdict = "UNKNOWN";
  let confidence = "LOW";

  if (status.startsWith("BLOCKED_HIGH_RISK")) {
    computerVerdict = "PASS";
    confidence = "HIGH";
    reasons.push("High-risk payment/trade task was safely blocked.");
  } else if (status === "BLOCKED_APP_NOT_INSTALLED") {
    computerVerdict = "UNKNOWN";
    confidence = "HIGH";
    blockers.push("Target app is not installed or package name did not resolve.");
  } else if (status === "FAILED_TIMEOUT") {
    computerVerdict = "FAIL";
    confidence = "HIGH";
    blockers.push("Task timed out before writing a flow report.");
  } else if (status.startsWith("PARTIAL")) {
    computerVerdict = evidence.localPngCount > 0 || evidence.localSnapshotCount > 0 ? "UNKNOWN" : "FAIL";
    confidence = "MEDIUM";
    blockers.push("UI graph exploration timed out or was partial.");
    if (evidence.localPngCount > 0) reasons.push("Some screenshots were still captured for diagnosis.");
  } else if (status === "SUCCESS_UI_GRAPH_EXPLORED") {
    if (taskHasFailedUiGraphEvents(task)) {
      computerVerdict = "UNKNOWN";
      confidence = "MEDIUM";
      blockers.push("Phone marked UI graph success but events include FAILED_NO_AFTER_SCREEN.");
    } else if (evidence.localPngCount > 0 || evidence.localSnapshotCount > 0) {
      computerVerdict = "PASS";
      confidence = "MEDIUM";
      reasons.push("UI graph task produced local screenshots or snapshots.");
    } else {
      computerVerdict = "UNKNOWN";
      blockers.push("UI graph success has no local screenshot/snapshot evidence.");
    }
  } else if (status === "SUCCESS") {
    const hardGoal = hasHardGoalEvidence(flow, text);
    if (hardGoal && evidence.localPngCount > 0) {
      computerVerdict = "PASS";
      confidence = "MEDIUM";
      reasons.push("Phone flow reports success with goal/verification text and local screenshot evidence.");
    } else {
      computerVerdict = "UNKNOWN";
      confidence = "MEDIUM";
      blockers.push("Phone marked success, but hard goal evidence is incomplete.");
      if (evidence.localPngCount === 0) blockers.push("No local PNG evidence for this task.");
    }
  } else if (status.startsWith("FAILED")) {
    computerVerdict = "FAIL";
    confidence = "HIGH";
    blockers.push(`Phone task status is ${status}.`);
  }

  if (ai.http402Count > 0) {
    blockers.push(`DeepSeek/v4pro API returned HTTP 402 ${ai.http402Count} time(s).`);
  }
  if (ai.jobCount > 0) {
    reasons.push(`AI jobs observed: ${ai.jobCount}, model(s): ${Object.keys(ai.models).join(", ") || "unknown"}.`);
  }
  if (artifacts.skillJsonCount > 0 || artifacts.appConfigCount > 0) {
    reasons.push(`Generated artifacts: skillJson=${artifacts.skillJsonCount}, appConfig=${artifacts.appConfigCount}.`);
  }

  return {
    appName: app.appName,
    packageName: app.resolvedPackage || app.packageName,
    taskId: task.taskId,
    instruction: task.instruction,
    phoneStatus: status,
    computerVerdict,
    confidence,
    message: task.message || "",
    flowReportFile: flowFile,
    blockers,
    reasons,
    ai,
    artifacts,
    evidence,
  };
}

function summarizeEvidence(task) {
  const devicePaths = collectDevicePaths(task);
  const local = devicePaths.map((p) => pathMap.get(p.replaceAll("\\", "/"))).filter(Boolean);
  const png = local.filter((p) => /\.png$/i.test(p));
  const snapshots = local.filter((p) => /ui_snapshots/i.test(p) && /\.json$/i.test(p));
  const flowReports = local.filter((p) => /flow_reports/i.test(p) && /\.json$/i.test(p));
  return {
    referencedPathCount: devicePaths.length,
    localPathCount: local.length,
    missingPathCount: Math.max(0, devicePaths.length - local.length),
    localPngCount: png.length,
    localSnapshotCount: snapshots.length,
    localFlowReportCount: flowReports.length,
    pngSamples: png.slice(0, 5),
    snapshotSamples: snapshots.slice(0, 5),
  };
}

function summarizeAi(task) {
  const paths = collectDevicePaths(task)
    .map((p) => pathMap.get(p.replaceAll("\\", "/")))
    .filter((p) => p && /ai_jobs/i.test(p) && /\.json$/i.test(p));
  const seen = new Set();
  const models = {};
  let successCount = 0;
  let failedCount = 0;
  let http402Count = 0;
  let http200Count = 0;
  for (const file of paths) {
    if (seen.has(file)) continue;
    seen.add(file);
    const job = readJsonSafe(file);
    if (!job) continue;
    const raw = JSON.stringify(job);
    const status = String(job.status || job.aiStatus || "");
    if (status === "SUCCESS") successCount++;
    if (status === "FAILED" || raw.includes("HTTP=402")) failedCount++;
    if (raw.includes("HTTP=402") || raw.includes("HTTP 402")) http402Count++;
    if (raw.includes("HTTP=200") || raw.includes("HTTP 200")) http200Count++;
    const model = job.model || job.modelName || job.request?.model || "unknown";
    models[model] = (models[model] || 0) + 1;
  }
  return {
    jobCount: seen.size,
    successCount,
    failedCount,
    http402Count,
    http200Count,
    models,
    samples: [...seen].slice(0, 5),
  };
}

function summarizeArtifacts(task) {
  const locals = collectDevicePaths(task)
    .map((p) => pathMap.get(p.replaceAll("\\", "/")))
    .filter(Boolean);
  return {
    skillJsonCount: locals.filter((p) => /skill_json_candidates/i.test(p)).length,
    dryRunReportCount: locals.filter((p) => /skill_json_dry_run_reports/i.test(p)).length,
    appConfigCount: locals.filter((p) => /app_config_exports/i.test(p)).length,
    runtimeReportCount: locals.filter((p) => /runtime[\\/]+run_reports/i.test(p)).length,
    graphCount: locals.filter((p) => /app_ui_graphs/i.test(p)).length,
  };
}

function taskHasFailedUiGraphEvents(task) {
  const events = task.flowReport?.events || task.flowReport?.exploreReport?.events || [];
  return JSON.stringify(events).includes("FAILED_NO_AFTER_SCREEN");
}

function hasHardGoalEvidence(flow, text) {
  if (String(flow.status || "") === "SUCCESS" && /goal|校验|验证|result|结果|路线|收藏|电话|搜索/.test(text)) {
    return true;
  }
  const steps = flow.steps || [];
  return steps.some((s) => String(s.status || "") === "SUCCESS" && /goal|校验|验证|完成/.test(`${s.title || ""} ${s.message || ""}`));
}

function collectDevicePaths(value, out = []) {
  if (value == null) return out;
  if (Array.isArray(value)) {
    for (const item of value) collectDevicePaths(item, out);
    return out;
  }
  if (typeof value === "object") {
    for (const nested of Object.values(value)) collectDevicePaths(nested, out);
    return out;
  }
  if (typeof value === "string") {
    const regex = /\/data\/user\/0\/com\.macropilot\.app\/files\/[^\s"',)]+/g;
    let match;
    while ((match = regex.exec(value)) !== null) {
      out.push(match[0].replace(/\\\//g, "/"));
    }
  }
  return [...new Set(out)];
}

function loadEvidenceManifests() {
  const root = path.join(repoRoot, "evidence_bundles");
  const out = [];
  if (!fs.existsSync(root)) return out;
  walk(root, (file) => {
    if (path.basename(file) !== "manifest.json") return;
    const manifest = readJsonSafe(file);
    if (manifest) out.push(manifest);
  });
  return out;
}

function buildDevicePathMap(manifests) {
  const map = new Map();
  for (const manifest of manifests) {
    for (const item of manifest.pulled || []) {
      if (item.devicePath && item.localPath && fs.existsSync(item.localPath)) {
        map.set(String(item.devicePath).replaceAll("\\", "/"), item.localPath);
      }
    }
  }
  return map;
}

function walk(dir, visit) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) walk(full, visit);
    else visit(full);
  }
}

function readJson(file) {
  return JSON.parse(fs.readFileSync(file, "utf8").replace(/^\uFEFF/, ""));
}

function readJsonSafe(file) {
  try {
    return readJson(file);
  } catch (_) {
    return null;
  }
}

function renderMarkdown(report) {
  const lines = [];
  lines.push("# 20 App 批次电脑侧复核报告");
  lines.push("");
  lines.push(`源报告：${report.summary.sourceReport}`);
  lines.push("");
  lines.push("## 总览");
  lines.push("");
  lines.push(`- 手机端批次状态：${report.summary.phoneStatus}`);
  lines.push(`- 手机端统计：success=${report.summary.phoneSuccess}, failed=${report.summary.phoneFailed}, blocked=${report.summary.phoneBlocked}, executedAttempts=${report.summary.executedAttempts}`);
  lines.push(`- 电脑侧复核：PASS=${report.summary.computerPass}, FAIL=${report.summary.computerFail}, UNKNOWN=${report.summary.computerUnknown}`);
  lines.push(`- v4pro/AI：jobs=${report.summary.aiJobs}, success=${report.summary.aiSuccess}, failed=${report.summary.aiFailed}, HTTP402=${report.summary.aiHttp402}`);
  lines.push(`- 证据：localPngs=${report.summary.localPngs}, pulledFiles=${report.summary.pulledEvidenceFiles}, failedPulls=${report.summary.failedEvidenceFiles}`);
  lines.push("");
  lines.push("## 逐 App 结果");
  lines.push("");
  lines.push("| App | Task | Phone | Computer | AI | Evidence | Main Blocker |");
  lines.push("| --- | --- | --- | --- | --- | --- | --- |");
  for (const row of report.rows) {
    const ai = `jobs ${row.ai.jobCount}, 402 ${row.ai.http402Count}`;
    const evidence = `png ${row.evidence.localPngCount}, snap ${row.evidence.localSnapshotCount}`;
    const blocker = row.blockers[0] || row.reasons[0] || "";
    lines.push(`| ${escapeMd(row.appName)} | ${escapeMd(row.taskId)} | ${escapeMd(row.phoneStatus)} | ${row.computerVerdict} ${row.confidence} | ${ai} | ${evidence} | ${escapeMd(blocker)} |`);
  }
  lines.push("");
  lines.push("## 关键结论");
  lines.push("");
  lines.push("- 这轮确实由 MacroPilot 手机端通过无障碍/输入法/API 执行；ADB 只用于触发、轮询和拉取证据。");
  lines.push("- v4pro 已被调用，但多数 AI job 返回 HTTP 402，导致任务退回本地 archetype/旧 Skill 组合，复杂任务大面积失败。");
  lines.push("- 批次中存在 Skill JSON、dry-run、app_config、runtime report、UI snapshot 和 PNG 证据，但成功判定仍偏弱，需要继续修目标校验和批次 verifier。");
  lines.push("- 支付宝、云闪付等高风险入口按安全策略拦截，这类不能自动输入密码、支付或交易。");
  lines.push("");
  return `${lines.join("\n")}\n`;
}

function escapeMd(value) {
  return String(value ?? "").replace(/\|/g, "\\|").replace(/\n/g, " ").slice(0, 220);
}

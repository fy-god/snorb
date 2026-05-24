const fs = require("fs");
const path = require("path");

const repoRoot = path.resolve(__dirname, "..");
const outDir = path.join(repoRoot, "docs");
const outJson = path.join(outDir, "COMPUTER_SIDE_TASK_VERIFICATION_20260521.json");
const outMd = path.join(outDir, "COMPUTER_SIDE_TASK_VERIFICATION_20260521.md");

const inputFiles = process.argv.slice(2);
const defaultFiles = [
  "bili_open_first_regression_20260521_001_flow.json",
  "xhs_flow_after_goalfix_20260520_000750.json",
  "hupu_success_flow_1778364597216.json",
  "douyin_final2_flow.json"
];

const files = (inputFiles.length ? inputFiles : defaultFiles)
  .map((file) => path.resolve(repoRoot, file))
  .filter((file) => fs.existsSync(file) && fs.statSync(file).size > 0);
const evidenceManifests = loadEvidenceManifests();

function readJson(file) {
  const raw = fs.readFileSync(file);
  const text = decodeMaybeUtf16(raw);
  return JSON.parse(text);
}

function collectPaths(value, out = []) {
  if (value == null) return out;
  if (Array.isArray(value)) {
    for (const item of value) collectPaths(item, out);
    return out;
  }
  if (typeof value === "object") {
    for (const [key, nested] of Object.entries(value)) {
      if (/path$/i.test(key) || /screenshot/i.test(key) || /snapshot/i.test(key)) {
        if (typeof nested === "string" && nested && nested !== "null") out.push(nested);
      }
      collectPaths(nested, out);
    }
    return out;
  }
  return out;
}

function localizePath(rawPath) {
  if (!rawPath || typeof rawPath !== "string") return null;
  const normalized = rawPath.replaceAll("\\", "/");
  const mapped = evidenceManifests.get(normalized);
  if (mapped && fs.existsSync(mapped)) return mapped;
  const localDirect = path.resolve(rawPath);
  if (fs.existsSync(localDirect)) return localDirect;
  const base = path.basename(normalized);
  const directRoot = path.join(repoRoot, base);
  if (fs.existsSync(directRoot)) return directRoot;
  return null;
}

function loadEvidenceManifests() {
  const map = new Map();
  const root = path.join(repoRoot, "evidence_bundles");
  if (!fs.existsSync(root)) return map;
  const manifests = [];
  walk(root, (file) => {
    if (path.basename(file) === "manifest.json") manifests.push(file);
  });
  for (const manifestPath of manifests) {
    try {
      const manifest = JSON.parse(fs.readFileSync(manifestPath, "utf8"));
      for (const item of manifest.pulled || []) {
        if (item.devicePath && item.localPath) {
          map.set(String(item.devicePath).replaceAll("\\", "/"), item.localPath);
        }
      }
    } catch (_) {
      // Ignore corrupt manifests; the verifier reports missing evidence below.
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

function imageEvidence(report) {
  const rawPaths = [...new Set(collectPaths(report))]
    .filter((p) => /\.(png|jpg|jpeg|webp|json)$/i.test(p));
  const local = [];
  const missing = [];
  for (const raw of rawPaths) {
    const resolved = localizePath(raw);
    if (resolved) local.push({ raw, local: resolved });
    else missing.push(raw);
  }
  const pngs = local.filter((item) => /\.(png)$/i.test(item.local));
  const analyzed = [];
  for (const item of pngs.slice(0, 20)) {
    analyzed.push(analyzePng(item.local, item.raw));
  }
  return {
    referenced: rawPaths.length,
    local: local.length,
    missing: missing.length,
    missingExamples: missing.slice(0, 8),
    pngCount: pngs.length,
    analyzed
  };
}

function analyzePng(file, raw) {
  const bytes = fs.readFileSync(file);
  const validPng = bytes.length >= 24 &&
    bytes[0] === 0x89 && bytes[1] === 0x50 && bytes[2] === 0x4e && bytes[3] === 0x47;
  let width = null;
  let height = null;
  if (validPng) {
    width = bytes.readUInt32BE(16);
    height = bytes.readUInt32BE(20);
  }
  const redDot = estimateRedDotEvidence(file);
  return {
    raw,
    local: file,
    validPng,
    sizeBytes: bytes.length,
    width,
    height,
    plausibleScreenshot: validPng && bytes.length > 20_000 && width >= 300 && height >= 500,
    redDotEvidence: redDot
  };
}

function estimateRedDotEvidence(file) {
  try {
    const child = require("child_process").spawnSync(
      "python",
      ["-c", `
from PIL import Image
import json, sys
p=sys.argv[1]
img=Image.open(p).convert("RGB")
w,h=img.size
regions=[("top",0,0,w,int(h*0.22)),("bottom",0,int(h*0.68),w,h),("right",int(w*0.70),0,w,h)]
out={}
for name,x0,y0,x1,y1 in regions:
    red=0
    total=0
    for y in range(y0,y1,max(1,(y1-y0)//180 or 1)):
        for x in range(x0,x1,max(1,(x1-x0)//180 or 1)):
            r,g,b=img.getpixel((x,y))
            total+=1
            if r>=180 and r>g*1.55 and r>b*1.45 and g<140 and b<140:
                red+=1
    out[name]={"sampled":total,"red":red,"ratio":(red/total if total else 0)}
print(json.dumps(out,ensure_ascii=False))
`, file],
      { encoding: "utf8", timeout: 10_000 }
    );
    if (child.status !== 0 || !child.stdout.trim()) return { ok: false, reason: child.stderr.trim() || "python_failed" };
    return { ok: true, regions: JSON.parse(child.stdout) };
  } catch (error) {
    return { ok: false, reason: error.message };
  }
}

function decodeMaybeUtf16(buffer) {
  if (buffer.length >= 2) {
    if (buffer[0] === 0xff && buffer[1] === 0xfe) return buffer.toString("utf16le").replace(/^\uFEFF/, "");
    if (buffer[0] === 0xfe && buffer[1] === 0xff) throw new Error("UTF-16BE is not supported");
  }
  if (buffer.length >= 4 && buffer[1] === 0 && buffer[3] === 0) {
    return buffer.toString("utf16le").replace(/^\uFEFF/, "");
  }
  return buffer.toString("utf8").replace(/^\uFEFF/, "");
}

function allSteps(report) {
  return report.steps || report.events || [];
}

function textOf(obj) {
  return [obj.title, obj.status, obj.message, obj.path, JSON.stringify(obj.extra || {})]
    .filter(Boolean)
    .join(" ");
}

function inferTaskType(report, file) {
  const haystack = [
    file,
    report.instruction,
    JSON.stringify(allSteps(report).map((s) => [s.title, s.message, s.extra]).slice(-8))
  ].join(" ").toLowerCase();
  if (haystack.includes("bilibili") || haystack.includes("bili") || haystack.includes("tv.danmaku.bili")) {
    if (haystack.includes("first_video") || haystack.includes("第一个") || haystack.includes("视频")) return "bilibili_open_first_video";
    return "bilibili_generic";
  }
  if (haystack.includes("xiaohongshu") || haystack.includes("xhs") || haystack.includes("com.xingin.xhs") || haystack.includes("小红书")) {
    if (haystack.includes("搜索入口") || haystack.includes("search entry")) return "xhs_search_entry";
    return "xhs_generic";
  }
  if (haystack.includes("hupu") || haystack.includes("虎扑")) return "hupu_publish_or_post";
  if (haystack.includes("douyin") || haystack.includes("aweme") || haystack.includes("抖音")) return "douyin_publish_video";
  return "unknown";
}

function evidenceSummary(report) {
  const steps = allSteps(report);
  const goalSteps = steps.filter((s) => `${s.title || ""}`.includes("目标完成校验") || `${s.title || ""}`.toLowerCase().includes("goal"));
  const runtimeSteps = steps.filter((s) => /run_report|runtime\/run_reports|执行结果|Runtime/i.test(textOf(s)));
  const graphSteps = steps.filter((s) => /app_ui_graph|AppUI|图谱|graph_candidate/i.test(textOf(s)));
  const aiSteps = steps.filter((s) => /AI|deepseek|v4|模型|checkpoint|replan/i.test(textOf(s)));
  const dryRunSteps = steps.filter((s) => /dry-run|dry run|静态检查/i.test(textOf(s)));
  const screenshotLike = steps.filter((s) => /screenshot|screen|截图|snapshot|快照|\.png/i.test(textOf(s)));
  const failedSteps = steps.filter((s) => `${s.status || ""}`.startsWith("FAILED"));
  return { steps, goalSteps, runtimeSteps, graphSteps, aiSteps, dryRunSteps, screenshotLike, failedSteps };
}

function verify(report, file) {
  const type = inferTaskType(report, file);
  const ev = evidenceSummary(report);
  const img = imageEvidence(report);
  const reasons = [];
  const blockers = [];
  let verdict = "UNKNOWN";
  let confidence = "LOW";

  if (report.status && `${report.status}`.startsWith("FAILED")) {
    blockers.push(`report_status=${report.status}`);
    return finish("FAIL", "HIGH");
  }
  if (ev.failedSteps.length) {
    blockers.push(`failed_step=${ev.failedSteps[0].title || ev.failedSteps[0].status}`);
  }

  if (type === "bilibili_open_first_video") {
    const goal = ev.goalSteps.find((s) => `${s.status}` === "SUCCESS" && s.extra);
    if (!goal) {
      blockers.push("missing_goal_success_step");
      return finish("UNKNOWN", "LOW");
    }
    const extra = goal.extra || {};
    const strong =
      extra.expected === "bilibili_first_video_detail_opened" &&
      extra.searchSurfaceVisible === false &&
      extra.videoSpecificEvidence === true &&
      extra.detailEvidenceStrong === true &&
      extra.shareSheetVisible === false &&
      extra.unsafePostOrMessageSurface === false &&
      Array.isArray(extra.videoResourceHits) &&
      extra.videoResourceHits.length > 0;
    if (strong) {
      reasons.push("B 站视频详情页证据强：搜索框/结果 Tab 消失，存在 video/player/danmaku 资源，且非分享页/消息页。");
      if (img.local === 0) reasons.push("未找到本地截图文件，当前 PASS 主要来自节点/资源证据；需要拉取截图后才能做视觉复核。");
      return finish("PASS", "HIGH");
    }
    blockers.push("bilibili_goal_evidence_not_strong_enough");
    return finish("FAIL", "MEDIUM");
  }

  if (type === "xhs_search_entry") {
    const goal = ev.goalSteps.find((s) => `${s.status}` === "SUCCESS");
    const hasSearchText = ev.steps.some((s) => /搜索入口|搜索框|search/i.test(textOf(s)));
    const hasRuntime = ev.runtimeSteps.length > 0;
    if (goal && hasSearchText && hasRuntime) {
      reasons.push("小红书搜索入口任务有目标校验成功、搜索入口文案和 runtime 报告。");
      if (img.local === 0) reasons.push("未找到本地截图文件，当前 PASS 主要来自节点/文本证据。");
      return finish("PASS", "MEDIUM");
    }
    blockers.push("xhs_search_entry_lacks_structured_goal_or_runtime");
    return finish("UNKNOWN", "LOW");
  }

  if (type === "hupu_publish_or_post") {
    const goal = ev.goalSteps.find((s) => `${s.status}` === "SUCCESS");
    const text = JSON.stringify(report);
    const successMarker = /发布成功|发帖成功|发表成功|审核中|帖子详情|Hupu publish feedback|post content is visible/i.test(text);
    const blockerMarker = /请选择专区|选择专区|登录|未登录|发布失败|网络异常|FAILED_HUPU/i.test(text);
    if (blockerMarker) {
      blockers.push("hupu_blocker_marker_present");
      return finish("FAIL", "HIGH");
    }
    if (goal && successMarker) {
      reasons.push("虎扑发帖存在目标校验成功和发布/审核/帖子详情证据。");
      if (img.local === 0) reasons.push("未找到本地截图文件，当前 PASS 主要来自节点/文本证据。");
      return finish("PASS", "MEDIUM");
    }
    blockers.push("hupu_publish_lacks_hard_success_marker");
    return finish("UNKNOWN", "LOW");
  }

  if (type === "douyin_publish_video") {
    const text = JSON.stringify(report);
    const successMarker = /作品发布成功|已发布|审核中|上传成功|发布中|正在发布|投稿成功/i.test(text);
    const editorOnly = /下一步|作品描述|说点什么|发作品|保存草稿/i.test(text);
    const blockerMarker = /登录|手机号|实名|权限|发布失败|上传失败|网络异常|风险|操作频繁/i.test(text);
    if (blockerMarker) {
      blockers.push("douyin_blocker_marker_present");
      return finish("FAIL", "HIGH");
    }
    if (successMarker) {
      reasons.push("抖音发布任务存在发布中/审核中/已发布等平台反馈。");
      if (img.local === 0) reasons.push("未找到本地截图文件，当前 PASS 主要来自节点/文本证据。");
      return finish("PASS", "MEDIUM");
    }
    if (editorOnly) {
      blockers.push("douyin_only_editor_or_publish_page_visible");
      return finish("FAIL", "MEDIUM");
    }
    blockers.push("douyin_publish_lacks_platform_feedback");
    return finish("UNKNOWN", "LOW");
  }

  const hasGoalSuccess = ev.goalSteps.some((s) => `${s.status}` === "SUCCESS");
  const hasRuntime = ev.runtimeSteps.length > 0;
  const hasGraph = ev.graphSteps.length > 0;
  if (hasGoalSuccess && hasRuntime) {
    reasons.push("通用任务存在目标校验成功和 runtime 执行报告，但缺专用 verifier。");
    return finish("PASS", "LOW");
  }
  if (report.status === "SUCCESS" && !hasGoalSuccess) {
    blockers.push("report_success_without_goal_verification");
    return finish("UNKNOWN", "LOW");
  }
  blockers.push("unknown_task_type_or_missing_evidence");
  return finish("UNKNOWN", "LOW");

  function finish(nextVerdict, nextConfidence) {
    verdict = nextVerdict;
    confidence = nextConfidence;
    return {
      file,
      taskType: type,
      reportedStatus: report.status || null,
      verdict,
      confidence,
      reasons,
      blockers,
      imageEvidence: img,
      evidenceCounts: {
        steps: ev.steps.length,
        goalSteps: ev.goalSteps.length,
        runtimeSteps: ev.runtimeSteps.length,
        graphSteps: ev.graphSteps.length,
        aiSteps: ev.aiSteps.length,
        dryRunSteps: ev.dryRunSteps.length,
        screenshotOrSnapshotSteps: ev.screenshotLike.length,
        referencedImageOrSnapshotPaths: img.referenced,
        localImageOrSnapshotPaths: img.local,
        missingImageOrSnapshotPaths: img.missing,
        analyzedPngs: img.analyzed.length,
        failedSteps: ev.failedSteps.length
      },
      requiredRule: ruleFor(type)
    };
  }
}

function ruleFor(type) {
  switch (type) {
    case "bilibili_open_first_video":
      return "必须有目标完成校验 SUCCESS，且 evidence.expected=bilibili_first_video_detail_opened、searchSurfaceVisible=false、videoSpecificEvidence=true、detailEvidenceStrong=true、shareSheetVisible=false。";
    case "xhs_search_entry":
      return "必须有目标完成校验 SUCCESS、搜索入口/搜索框证据和 runtime 报告。";
    case "hupu_publish_or_post":
      return "必须有发布成功/审核中/帖子详情等硬证据；出现请选择专区/登录/发布失败则 FAIL。";
    case "douyin_publish_video":
      return "必须有发布中/审核中/已发布等平台反馈；只停在编辑页/发布页不算成功。";
    default:
      return "通用任务至少需要目标完成校验 SUCCESS + runtime 报告；否则 UNKNOWN。";
  }
}

const results = [];
for (const file of files) {
  try {
    results.push(verify(readJson(file), file));
  } catch (error) {
    results.push({
      file,
      taskType: "parse_error",
      reportedStatus: null,
      verdict: "UNKNOWN",
      confidence: "LOW",
      reasons: [],
      blockers: [`parse_error=${error.message}`],
      evidenceCounts: {},
      requiredRule: "文件必须是可解析 JSON。"
    });
  }
}

const summary = {
  total: results.length,
  pass: results.filter((r) => r.verdict === "PASS").length,
  fail: results.filter((r) => r.verdict === "FAIL").length,
  unknown: results.filter((r) => r.verdict === "UNKNOWN").length
};

const output = {
  schemaVersion: 1,
  kind: "computer_side_task_verification_report",
  generatedAt: "2026-05-21",
  policy: [
    "手机端 status=SUCCESS 只是初判。",
    "电脑端只根据可审计证据复核 PASS / FAIL / UNKNOWN。",
    "缺目标校验、缺 runtime 报告、缺页面证据时不能算成功。",
    "支付、交易、下单、密码、认证类任务只允许验证安全拦截。"
  ],
  summary,
  results
};

fs.mkdirSync(outDir, { recursive: true });
writeUtf8Bom(outJson, `${JSON.stringify(output, null, 2)}\n`);

const md = [
  "# 电脑端任务完成复核报告",
  "",
  "生成时间：2026-05-21",
  "",
  "规则：不相信手机端一句 SUCCESS，必须重新读取 flow/runtime/goal evidence。证据不足就是 UNKNOWN。",
  "",
  `- 总数：${summary.total}`,
  `- PASS：${summary.pass}`,
  `- FAIL：${summary.fail}`,
  `- UNKNOWN：${summary.unknown}`,
  "",
  "## 明细",
  ""
];

for (const r of results) {
  md.push(`### ${path.basename(r.file)}`);
  md.push("");
  md.push(`- 任务类型：${r.taskType}`);
  md.push(`- 手机端状态：${r.reportedStatus || "-"}`);
  md.push(`- 电脑复核：${r.verdict} (${r.confidence})`);
  md.push(`- 证据数量：${JSON.stringify(r.evidenceCounts)}`);
  md.push(`- 图像证据：引用 ${r.imageEvidence?.referenced ?? 0}，本地可读 ${r.imageEvidence?.local ?? 0}，缺失 ${r.imageEvidence?.missing ?? 0}，已分析 PNG ${r.imageEvidence?.analyzed?.length ?? 0}`);
  if (r.imageEvidence?.missingExamples?.length) md.push(`- 未拉取示例：${r.imageEvidence.missingExamples.slice(0, 3).join("；")}`);
  const firstImage = r.imageEvidence?.analyzed?.find((img) => img.validPng);
  if (firstImage) {
    md.push(`- 首张有效图：${firstImage.width}x${firstImage.height}，${firstImage.sizeBytes} bytes，redDot=${JSON.stringify(firstImage.redDotEvidence?.regions || {})}`);
  }
  md.push(`- 规则：${r.requiredRule}`);
  if (r.reasons.length) md.push(`- 通过原因：${r.reasons.join("；")}`);
  if (r.blockers.length) md.push(`- 阻塞/不足：${r.blockers.join("；")}`);
  md.push("");
}

writeUtf8Bom(outMd, md.join("\n"));
console.log(JSON.stringify({ ok: true, summary, outJson, outMd }, null, 2));

function writeUtf8Bom(file, text) {
  fs.writeFileSync(file, `\uFEFF${text}`, "utf8");
}

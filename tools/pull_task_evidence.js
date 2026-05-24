const fs = require("fs");
const path = require("path");
const cp = require("child_process");

const repoRoot = path.resolve(__dirname, "..");
const packageName = "com.macropilot.app";
const input = process.argv[2];
if (!input) {
  console.error("Usage: node tools/pull_task_evidence.js <flow-or-completion-report.json>");
  process.exit(2);
}

const inputPath = path.resolve(repoRoot, input);
const report = readJson(inputPath);
const bundleRoot = path.join(repoRoot, "evidence_bundles");
const runId = `${path.basename(inputPath, path.extname(inputPath))}_${Date.now()}`;
const outDir = path.join(bundleRoot, runId);
fs.mkdirSync(outDir, { recursive: true });

const queue = [...new Set(collectDevicePaths(report))];
const pulled = [];
const failed = [];
const seen = new Set(queue);

for (let index = 0; index < queue.length; index++) {
  const devicePath = queue[index];
  const localPath = localPathFor(devicePath);
  fs.mkdirSync(path.dirname(localPath), { recursive: true });
  const result = pullDevicePath(devicePath, localPath);
  if (result.ok) {
    pulled.push({ devicePath, localPath, bytes: fs.statSync(localPath).size });
    if (/\.json$/i.test(localPath)) {
      const nested = readJsonSafe(localPath);
      if (nested) {
        for (const nestedPath of collectDevicePaths(nested)) {
          if (!seen.has(nestedPath)) {
            seen.add(nestedPath);
            queue.push(nestedPath);
          }
        }
      }
    }
  } else {
    failed.push({ devicePath, reason: result.reason });
  }
}

const manifest = {
  schemaVersion: 1,
  kind: "task_evidence_bundle_manifest",
  sourceReport: inputPath,
  bundleDir: outDir,
  pulledAt: "2026-05-21",
  pulledCount: pulled.length,
  failedCount: failed.length,
  pulled,
  failed
};
fs.writeFileSync(path.join(outDir, "manifest.json"), `${JSON.stringify(manifest, null, 2)}\n`, "utf8");
console.log(JSON.stringify({
  ok: true,
  sourceReport: inputPath,
  bundleDir: outDir,
  pulledCount: pulled.length,
  failedCount: failed.length,
  manifest: path.join(outDir, "manifest.json")
}, null, 2));

function readJson(file) {
  return JSON.parse(decodeMaybeUtf16(fs.readFileSync(file)));
}

function readJsonSafe(file) {
  try {
    return readJson(file);
  } catch (_) {
    return null;
  }
}

function decodeMaybeUtf16(buffer) {
  if (buffer.length >= 2) {
    if (buffer[0] === 0xff && buffer[1] === 0xfe) return buffer.toString("utf16le").replace(/^\uFEFF/, "");
  }
  if (buffer.length >= 4 && buffer[1] === 0 && buffer[3] === 0) {
    return buffer.toString("utf16le").replace(/^\uFEFF/, "");
  }
  return buffer.toString("utf8").replace(/^\uFEFF/, "");
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
  return out;
}

function localPathFor(devicePath) {
  const rel = devicePath
    .replace(/^\/data\/user\/0\/com\.macropilot\.app\/files\//, "")
    .replace(/[^A-Za-z0-9._/-]/g, "_");
  return path.join(outDir, rel);
}

function pullDevicePath(devicePath, localPath) {
  const rel = devicePath.replace(/^\/data\/user\/0\/com\.macropilot\.app\//, "");
  const cmd = `run-as ${packageName} cat ${shellQuote(rel)}`;
  const result = cp.spawnSync("adb", ["shell", cmd], {
    encoding: "buffer",
    maxBuffer: 80 * 1024 * 1024,
    timeout: 30_000
  });
  if (result.status !== 0 || !result.stdout || result.stdout.length === 0) {
    return {
      ok: false,
      reason: Buffer.concat([result.stderr || Buffer.alloc(0), result.stdout || Buffer.alloc(0)]).toString("utf8").trim() || `adb_status_${result.status}`
    };
  }
  fs.writeFileSync(localPath, result.stdout);
  return { ok: true };
}

function shellQuote(value) {
  return `'${String(value).replace(/'/g, `'\\''`)}'`;
}

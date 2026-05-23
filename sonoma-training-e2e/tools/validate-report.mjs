import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

export function validateRun({ scenarioPath, artifactPath, logcatPath, instrumentationPath, metadataPath }) {
  const scenario = readJsonIfPresent(scenarioPath);
  const artifact = readJsonIfPresent(artifactPath);
  const logcat = readTextIfPresent(logcatPath);
  const instrumentation = readTextIfPresent(instrumentationPath);
  const metadata = readJsonIfPresent(metadataPath) ?? {};
  const checks = [];

  const add = (name, status, detail = '') => checks.push({ name, status, detail });
  const pass = (name, detail) => add(name, 'pass', detail);
  const fail = (name, detail) => add(name, 'fail', detail);
  const skip = (name, detail) => add(name, 'skip', detail);

  if (instrumentation.includes('FAILURES!!!') || instrumentation.includes('INSTRUMENTATION_RESULT: shortMsg=Process crashed')) {
    fail('instrumentation', 'UI Automator instrumentation reported a failure');
  } else if (instrumentation.includes('OK (') || instrumentation.includes('INSTRUMENTATION_CODE: -1')) {
    pass('instrumentation', 'UI Automator completed without an instrumentation failure marker');
  } else {
    fail('instrumentation', 'No successful instrumentation completion marker found');
  }

  if (logcat.includes('Telemetry loop started source=phone_imu_gps cameraFusion=true')) {
    pass('telemetry_service_log', 'phone_imu_gps telemetry loop with camera fusion started');
  } else {
    fail('telemetry_service_log', 'Missing phone_imu_gps camera fusion startup log');
  }

  const fatalExceptionPattern = /(FATAL EXCEPTION|(?:^|\s)ANR(?:\s|$)|Application Not Responding)/im;
  const targetProcessDeathPattern = /Process\s+(com\.trustableai\.koru[.\w]*).*has died/i;
  const hasFatalException = fatalExceptionPattern.test(logcat);
  const targetProcessDeath = targetProcessDeathPattern.exec(logcat);
  if (hasFatalException) {
    fail('crash_free_log_window', 'Logcat contains a FATAL EXCEPTION or ANR');
  } else if (targetProcessDeath) {
    fail('crash_free_log_window', `Target process died: ${targetProcessDeath[1]}`);
  } else {
    pass('crash_free_log_window', 'No fatal exception, ANR, or target process death found');
  }

  if (!artifact) {
    fail('recorded_artifact_exists', `No artifact found at ${artifactPath}`);
    return { checks, summary: summarize(checks), scenario, artifact: null, metadata };
  }
  pass('recorded_artifact_exists', artifactPath);

  if (artifact.schemaVersion === 2) pass('artifact_schema', 'schemaVersion is 2');
  else fail('artifact_schema', `Expected schemaVersion 2, got ${artifact.schemaVersion}`);

  if (artifact.trackName === 'Sonoma Raceway' || artifact.summary?.trackName === 'Sonoma Raceway') {
    pass('artifact_track', 'track is Sonoma Raceway');
  } else {
    fail('artifact_track', `Expected Sonoma Raceway, got ${artifact.trackName ?? artifact.summary?.trackName}`);
  }

  const frames = Array.isArray(artifact.frames) ? artifact.frames : [];
  const minFrames = scenario?.expected?.minFrames ?? 1;
  if (frames.length >= minFrames) pass('artifact_frame_count', `${frames.length} frames >= ${minFrames}`);
  else fail('artifact_frame_count', `${frames.length} frames < ${minFrames}`);

  if (frames.some((frame) => String(frame.telemetrySource ?? '').includes('phone_imu_gps'))) {
    pass('artifact_source', 'at least one frame is from phone_imu_gps');
  } else {
    fail('artifact_source', 'no frame recorded telemetrySource=phone_imu_gps');
  }

  if (metadata.startedAtMs && artifact.startedAt && artifact.startedAt < metadata.startedAtMs - 5_000) {
    fail('artifact_freshness', `artifact startedAt ${artifact.startedAt} predates run start ${metadata.startedAtMs}`);
  } else {
    pass('artifact_freshness', 'artifact timestamp is fresh for this run or metadata is unavailable');
  }

  const decisions = Array.isArray(artifact.decisions) ? artifact.decisions : [];
  const hasFeedforward = decisions.some((decision) => normalize(decision.path) === 'feedforward');
  const hasHot = decisions.some((decision) => normalize(decision.path) === 'hot');
  const p0BrakeDecisions = decisions.filter(
    (decision) => decision.priority === 0 && decision.action === 'BRAKE' && decision.text === 'Brake now',
  );

  if (hasFeedforward) pass('decision_feedforward', 'found at least one FEEDFORWARD decision');
  else fail('decision_feedforward', 'missing FEEDFORWARD decision');
  if (hasHot) pass('decision_hot', 'found at least one HOT decision');
  else fail('decision_hot', 'missing HOT decision');
  if (p0BrakeDecisions.length > 0) pass('decision_p0_brake', 'found P0 BRAKE with text Brake now');
  else fail('decision_p0_brake', 'missing P0 BRAKE with text Brake now');

  const decisionIds = new Set(decisions.map((decision) => decision.id).filter(Boolean));
  const audioEvents = Array.isArray(artifact.audioEvents) ? artifact.audioEvents : [];
  const invalidAudioEvents = audioEvents.filter((event) => !event.decisionId || !decisionIds.has(event.decisionId));
  if (audioEvents.length > 0 && invalidAudioEvents.length === 0) {
    pass('audio_event_links', `${audioEvents.length} audio events link to decision IDs`);
  } else if (audioEvents.length === 0) {
    fail('audio_event_links', 'no audio events recorded');
  } else {
    fail('audio_event_links', `${invalidAudioEvents.length} audio events do not link to known decision IDs`);
  }

  const acceptableAudioStatuses = new Set(scenario?.expected?.requiredAudioStatuses ?? ['CLIP_STARTED', 'TTS_STARTED', 'TTS_QUEUED']);
  const p0DecisionIds = new Set(p0BrakeDecisions.map((decision) => decision.id).filter(Boolean));
  const p0Audio = audioEvents.filter((event) => p0DecisionIds.has(event.decisionId));
  if (p0Audio.some((event) => acceptableAudioStatuses.has(event.status))) {
    pass('p0_audio_semantics', 'P0 BRAKE has spoken clip or flushed TTS fallback evidence');
  } else {
    fail('p0_audio_semantics', 'P0 BRAKE has no CLIP_STARTED/TTS_STARTED/TTS_QUEUED audio evidence');
  }

  const hotLatencies = decisions
    .filter((decision) => normalize(decision.path) === 'hot' && Number.isFinite(decision.latencyMs))
    .map((decision) => decision.latencyMs);
  const hotP95 = percentile(hotLatencies, 0.95);
  const hotBudget = scenario?.expected?.latencyBudgets?.hotPathP95Ms ?? 50;
  if (hotLatencies.length > 0 && hotP95 <= hotBudget) {
    pass('hot_latency_p95', `p95=${hotP95.toFixed(2)}ms <= ${hotBudget}ms`);
  } else if (hotLatencies.length === 0) {
    fail('hot_latency_p95', 'no HOT decision latencies recorded');
  } else {
    fail('hot_latency_p95', `p95=${hotP95.toFixed(2)}ms > ${hotBudget}ms`);
  }

  const p0AudioBudget = scenario?.expected?.latencyBudgets?.p0AudioDispatchMs ?? 100;
  const p0DispatchLatencies = p0Audio
    .map((event) => event.dispatchLatencyMs)
    .filter((value) => Number.isFinite(value));
  const p0DispatchMax = p0DispatchLatencies.length ? Math.max(...p0DispatchLatencies) : null;
  if (p0DispatchMax != null && p0DispatchMax <= p0AudioBudget) {
    pass('p0_audio_dispatch_latency', `max=${p0DispatchMax.toFixed(2)}ms <= ${p0AudioBudget}ms`);
  } else if (p0DispatchMax == null) {
    fail('p0_audio_dispatch_latency', 'no P0 audio dispatch latency recorded');
  } else {
    fail('p0_audio_dispatch_latency', `max=${p0DispatchMax.toFixed(2)}ms > ${p0AudioBudget}ms`);
  }

  const expectsLiteRt = scenario?.expected?.optionalAccelerator === 'MEDIAPIPE_LITERT';
  const litertSeen = /MEDIAPIPE_LITERT|MediaPipe LiteRT|LiteRT/i.test(logcat);
  if (expectsLiteRt && litertSeen) {
    pass('gpu_optional', 'LiteRT/MediaPipe accelerator status appeared in logs');
  } else if (expectsLiteRt) {
    skip('gpu_optional', 'LiteRT model/status not observed; deterministic fallback is allowed for this scenario');
  }

  return { checks, summary: summarize(checks), scenario, artifact, metadata };
}

export function writeReports(result, reportDir) {
  mkdirSync(reportDir, { recursive: true });
  writeFileSync(resolve(reportDir, 'report.json'), `${JSON.stringify(result, null, 2)}\n`);
  writeFileSync(resolve(reportDir, 'report.md'), markdownReport(result));
  writeFileSync(resolve(reportDir, 'junit.xml'), junitReport(result));
}

function readJsonIfPresent(path) {
  if (!path || !existsSync(path)) return null;
  const text = readFileSync(path, 'utf8').trim();
  if (!text) return null;
  return JSON.parse(text);
}

function readTextIfPresent(path) {
  if (!path || !existsSync(path)) return '';
  return readFileSync(path, 'utf8');
}

function normalize(value) {
  return String(value ?? '').toLowerCase();
}

function percentile(values, quantile) {
  if (!values.length) return Number.NaN;
  const sorted = [...values].sort((a, b) => a - b);
  const index = Math.min(sorted.length - 1, Math.ceil(sorted.length * quantile) - 1);
  return sorted[index];
}

function summarize(checks) {
  const counts = { pass: 0, fail: 0, skip: 0 };
  for (const check of checks) counts[check.status] += 1;
  return counts;
}

function markdownReport(result) {
  const lines = [
    '# Sonoma Training E2E Report',
    '',
    `Summary: ${result.summary.pass} passed, ${result.summary.fail} failed, ${result.summary.skip} skipped.`,
    '',
    '| Status | Check | Detail |',
    '| --- | --- | --- |',
  ];
  for (const check of result.checks) {
    lines.push(`| ${check.status.toUpperCase()} | ${check.name} | ${escapeMarkdown(check.detail)} |`);
  }
  lines.push('');
  return `${lines.join('\n')}\n`;
}

function escapeMarkdown(value) {
  return String(value ?? '').replaceAll('|', '\\|').replaceAll('\n', '<br>');
}

function junitReport(result) {
  const failures = result.checks.filter((check) => check.status === 'fail');
  const skipped = result.checks.filter((check) => check.status === 'skip');
  const cases = result.checks.map((check) => {
    if (check.status === 'fail') {
      return `    <testcase name="${xml(check.name)}"><failure message="${xml(check.detail)}" /></testcase>`;
    }
    if (check.status === 'skip') {
      return `    <testcase name="${xml(check.name)}"><skipped message="${xml(check.detail)}" /></testcase>`;
    }
    return `    <testcase name="${xml(check.name)}" />`;
  });
  return [
    '<?xml version="1.0" encoding="UTF-8"?>',
    `<testsuite name="sonoma-training-e2e" tests="${result.checks.length}" failures="${failures.length}" skipped="${skipped.length}">`,
    ...cases,
    '</testsuite>',
    '',
  ].join('\n');
}

function xml(value) {
  return String(value ?? '')
    .replaceAll('&', '&amp;')
    .replaceAll('"', '&quot;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;');
}

function parseArgs(argv) {
  const parsed = {};
  for (let index = 0; index < argv.length; index += 1) {
    const arg = argv[index];
    if (!arg.startsWith('--')) continue;
    parsed[arg.slice(2)] = argv[index + 1];
    index += 1;
  }
  return parsed;
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const args = parseArgs(process.argv.slice(2));
  const result = validateRun({
    scenarioPath: args.scenario,
    artifactPath: args.artifact,
    logcatPath: args.logcat,
    instrumentationPath: args.instrumentation,
    metadataPath: args.metadata,
  });
  const reportDir = args.out ? resolve(args.out) : resolve(dirname(args.artifact ?? '.'), '.');
  writeReports(result, reportDir);
  console.log(`Report written to ${reportDir}: ${result.summary.pass} passed, ${result.summary.fail} failed, ${result.summary.skip} skipped`);
  if (result.summary.fail > 0) process.exit(1);
}

/** Freeze already verified local artifacts into a standalone acceptance bundle. */
import fs from 'node:fs/promises'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { createHash } from 'node:crypto'
import { latestResult } from './acceptance-evidence.mjs'
import { summarizeSurefireXml, verifyBuildAttestation } from './backend-build-evidence.mjs'
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const releaseName = process.argv[2] || '2026-10-16-acceptance'
if (!/^[a-zA-Z0-9-]+$/.test(releaseName)) throw new Error('Invalid release name')
const output = path.join(root, 'data', 'releases', releaseName)
try { await fs.access(output); throw new Error('Release already exists; choose a new name instead of overwriting it') }
catch (error) { if (error.code !== 'ENOENT') throw error }
const files = ['front/dist/index.html', 'ddd-agent-sheep-app/target/ai-agent-station-study-app.jar', 'scripts/acceptance-server.mjs', 'scripts/Stop-Acceptance.ps1', 'scripts/Verify-AcceptanceBundle.ps1']
await Promise.all(files.map(file => fs.access(path.join(root, file))))
const sessions = path.join(root, 'data', 'acceptance')
const actual = await latestResult(sessions, 'smoke-')
if (!actual.result.steps.some(step => step.includes('trusted proxy header stripping'))) throw new Error('Multi-identity browser evidence required')
const simulated = await latestResult(sessions, 'ui-failures-')
async function fingerprintDirectory(directory) {
  const entries = (await fs.readdir(directory, { withFileTypes: true })).sort((a, b) => a.name.localeCompare(b.name))
  const hash = createHash('sha256')
  for (const entry of entries) {
    const target = path.join(directory, entry.name)
    hash.update(entry.name + '\0')
    hash.update(entry.isDirectory() ? await fingerprintDirectory(target) : createHash('sha256').update(await fs.readFile(target)).digest('hex'))
  }
  return hash.digest('hex')
}
const buildIdentity = {
  jarSha256: createHash('sha256').update(await fs.readFile(path.join(root, files[1]))).digest('hex'),
  frontendSha256: await fingerprintDirectory(path.join(root, 'front/dist')),
  harnessSha256: createHash('sha256').update(await fs.readFile(path.join(root, files[2]))).digest('hex')
}
for (const evidence of [actual, simulated]) {
  if (!evidence.result.buildIdentity || Object.keys(buildIdentity).some(key => evidence.result.buildIdentity[key] !== buildIdentity[key])) {
    throw new Error('Browser evidence does not match current artifacts: ' + evidence.name + '; restart the harness and rerun browser verification')
  }
}
const reports = path.join(root, 'ddd-agent-sheep-app', 'target', 'surefire-reports')
const totals = { tests: 0, failures: 0, errors: 0, skipped: 0 }
const backendReportFiles = []
for (const file of await fs.readdir(reports)) {
  if (!/^TEST-.*\.xml$/.test(file)) continue
  const xml = await fs.readFile(path.join(reports, file), 'utf8')
  const result = summarizeSurefireXml(xml)
  backendReportFiles.push({ path: file, sha256: createHash('sha256').update(xml).digest('hex'), modifiedAt: (await fs.stat(path.join(reports, file))).mtime.toISOString(), ...result })
  for (const key of Object.keys(totals)) totals[key] += result[key]
}
if (!totals.tests || totals.failures || totals.errors) throw new Error('Backend test evidence is missing or failed')
const testRoot = path.join(root, 'ddd-agent-sheep-app', 'src', 'test', 'java')
async function sourceFiles(directory) {
  return (await Promise.all((await fs.readdir(directory, { withFileTypes: true })).map(entry => {
    const target = path.join(directory, entry.name)
    return entry.isDirectory() ? sourceFiles(target) : [target]
  }))).flat()
}
const expectedSuites = []
for (const file of (await sourceFiles(testRoot)).filter(file => /Test\.java$/.test(file))) {
  const source = await fs.readFile(file, 'utf8')
  const packageName = source.match(/^package\s+([\w.]+)\s*;/m)?.[1]
  if (!packageName) throw new Error('Test source has no package declaration: ' + file)
  expectedSuites.push(packageName + '.' + path.basename(file, '.java'))
}
expectedSuites.sort()
const expectedSourceFingerprints = []
for (const file of (await sourceFiles(testRoot)).filter(file => /Test\.java$/.test(file)).sort()) {
  const source = await fs.readFile(file)
  expectedSourceFingerprints.push({ path: path.relative(root, file).replaceAll(path.sep, '/'), sha256: createHash('sha256').update(source).digest('hex') })
}
const attestationNames = (await fs.readdir(sessions)).filter(name => name.startsWith('backend-build-') && name.endsWith('.json')).sort().reverse()
let buildAttestation = null
let buildAttestationPath = null
if (attestationNames.length) {
  buildAttestationPath = path.join(sessions, attestationNames[0])
  buildAttestation = JSON.parse(await fs.readFile(buildAttestationPath, 'utf8'))
  const jarPath = path.join(root, files[1])
  const verified = verifyBuildAttestation(buildAttestation, {
    jarSha256: buildIdentity.jarSha256,
    jarModifiedAt: (await fs.stat(jarPath)).mtime.toISOString(),
    expectedSuites,
    expectedSourceFingerprints,
    reports: backendReportFiles
  })
  if (!verified.valid) throw new Error('Latest backend build attestation is invalid: ' + verified.reason)
  if (JSON.stringify(verified.totals) !== JSON.stringify(totals)) throw new Error('Attested backend totals differ from bundled reports')
}
await fs.mkdir(output, { recursive: true })
async function copy(from, to) { await fs.mkdir(path.dirname(to), { recursive: true }); await fs.cp(from, to, { recursive: true, errorOnExist: true }) }
await copy(path.join(root, 'front/dist'), path.join(output, 'front/dist'))
for (const file of files.slice(1)) await copy(path.join(root, file), path.join(output, file))
await copy(path.join(root, '..', '设计方案'), path.join(output, '设计方案'))
await copy(path.join(sessions, actual.name), path.join(output, 'verification', 'real-flow'))
await copy(path.join(sessions, simulated.name), path.join(output, 'verification', 'ui-failures'))
for (const report of backendReportFiles) await copy(path.join(reports, report.path), path.join(output, 'verification', 'backend-reports', report.path))
if (buildAttestationPath) await copy(buildAttestationPath, path.join(output, 'verification', 'backend-build-attestation.json'))
await fs.writeFile(path.join(output, 'Start.ps1'), `param([int]$Port = 4174, [int]$BackendPort = 18092, [ValidateSet('LAWYER','PARALEGAL')][string]$Role = 'LAWYER')
$ErrorActionPreference = 'Stop'
& node (Join-Path $PSScriptRoot 'scripts/acceptance-server.mjs') "--port=$Port" "--backend-port=$BackendPort" "--role=$Role"
if ($LASTEXITCODE -ne 0) { throw 'Acceptance service failed; inspect the printed session log' }
`, 'utf8')
await fs.writeFile(path.join(output, 'Verify.ps1'), `$ErrorActionPreference = 'Stop'
& (Join-Path $PSScriptRoot 'scripts/Verify-AcceptanceBundle.ps1') -BundleRoot $PSScriptRoot
if (-not $?) { throw 'Acceptance bundle verification failed.' }
`, 'utf8')
const backendEvidenceText = buildAttestation
  ? 'Backend tests are bound to the JAR by verification/backend-build-attestation.json. The local attestation is unsigned. It records the Maven command, time window, JAR SHA-256, exact test-source suite set, and every Surefire XML hash.'
  : 'Backend test totals are an aggregate of existing Surefire XML reports and are not proven to come from the same build as the JAR.'
const readme = [
  '# Local Acceptance Bundle',
  '',
  'This package contains a frozen frontend, runnable backend JAR, source design references, and browser acceptance evidence. It is not a production release.',
  '',
  'Requirements: Node.js 22.12+ and Java 17+. In PowerShell, run ./Verify.ps1 then ./Start.ps1. The default browser endpoint is http://127.0.0.1:4174/matters and the default backend port is 18092.',
  '',
  'The browser harness uses local-only test identities and in-memory matter data. Do not enter real customer information. UI failure tests intentionally use simulated model/error responses and are not model quality evaluations.',
  '',
  backendEvidenceText,
  '',
  'See manifest.json for artifact fingerprints, verification group counts, test report hashes, and production-readiness caveats.',
  ''
].join('\n')
await fs.writeFile(path.join(output, 'README-acceptance.md'), readme, 'utf8')
async function collect(dir, base = '') {
  const found = []
  for (const entry of await fs.readdir(dir, { withFileTypes: true })) {
    const relative = base ? `${base}/${entry.name}` : entry.name
    const absolute = path.join(dir, entry.name)
    if (entry.isDirectory()) found.push(...await collect(absolute, relative))
    else found.push({ path: relative, bytes: (await fs.stat(absolute)).size, sha256: createHash('sha256').update(await fs.readFile(absolute)).digest('hex') })
  }
  return found.sort((a, b) => a.path.localeCompare(b.path))
}
// Validate the copied bytes, not only the source files inspected before copying.
const copiedIdentity = {
  jarSha256: createHash('sha256').update(await fs.readFile(path.join(output, files[1]))).digest('hex'),
  frontendSha256: await fingerprintDirectory(path.join(output, 'front/dist')),
  harnessSha256: createHash('sha256').update(await fs.readFile(path.join(output, files[2]))).digest('hex')
}
if (Object.keys(buildIdentity).some(key => copiedIdentity[key] !== buildIdentity[key])) {
  throw new Error('Artifacts changed while copying; incomplete output has no manifest and must not be used: ' + output)
}
for (const report of backendReportFiles) {
  const copied = await fs.readFile(path.join(output, 'verification/backend-reports', report.path))
  if (createHash('sha256').update(copied).digest('hex') !== report.sha256) {
    throw new Error('Backend report changed while copying: ' + report.path)
  }
}
if (buildAttestationPath) {
  const copied = JSON.parse(await fs.readFile(path.join(output, 'verification/backend-build-attestation.json'), 'utf8'))
  if (JSON.stringify(copied) !== JSON.stringify(buildAttestation)) throw new Error('Backend build attestation changed while copying')
}
for (const [folder, evidence] of [['real-flow', actual], ['ui-failures', simulated]]) {
  const copied = JSON.parse(await fs.readFile(path.join(output, 'verification', folder, 'result.json'), 'utf8'))
  if (JSON.stringify(copied) !== JSON.stringify(evidence.result)) throw new Error('Browser result changed while copying: ' + folder)
}
const backendTests = buildAttestation
  ? { ...totals, evidenceType: 'same-run-build-attestation', sameBuildAsJarVerified: true, attestation: 'verification/backend-build-attestation.json', reports: backendReportFiles }
  : { ...totals, evidenceType: 'aggregate-existing-surefire-reports', sameBuildAsJarVerified: false, note: 'Reports may originate from separate full and targeted runs; totals do not establish a single clean build or bind tests to the JAR.', reports: backendReportFiles }
const manifest = { release: releaseName, frozenAt: new Date().toISOString(), sourceProject: root,
  productionReady: false, buildIdentity, backendTests, frontendUnitTests: { command: 'npm --prefix front test', note: 'See dated verification log for test counts; the bundle does not infer test execution.' },
  realBrowser: { source: actual.name, groups: actual.result.steps.length },
  simulatedBrowser: { source: simulated.name, groups: simulated.result.steps.length, simulatedResponses: true },
  caveat: 'Local backend build attestation is unsigned; this is a local acceptance bundle, not a production release.',
  files: await collect(output) }
await fs.writeFile(path.join(output, 'manifest.json'), JSON.stringify(manifest, null, 2), 'utf8')
console.log(JSON.stringify({ output, backendTests: totals, realGroups: manifest.realBrowser.groups, simulatedGroups: manifest.simulatedBrowser.groups, files: manifest.files.length }, null, 2))

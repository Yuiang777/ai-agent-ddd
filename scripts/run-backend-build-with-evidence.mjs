import fs from 'node:fs/promises'
import path from 'node:path'
import { spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import { sha256, summarizeSurefireXml, verifyBuildAttestation } from './backend-build-evidence.mjs'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const command = 'mvn -B -pl ddd-agent-sheep-app -am package'
const testRoot = path.join(root, 'ddd-agent-sheep-app', 'src', 'test', 'java')
async function walk(directory) {
  const entries = await fs.readdir(directory, { withFileTypes: true })
  return (await Promise.all(entries.map(entry => {
    const target = path.join(directory, entry.name)
    return entry.isDirectory() ? walk(target) : [target]
  }))).flat()
}
async function snapshotTestSources() {
  const sources = (await walk(testRoot)).filter(file => /Test\.java$/.test(file)).sort()
  const fingerprints = []
  const suites = []
  for (const file of sources) {
    const bytes = await fs.readFile(file)
    const text = bytes.toString('utf8')
    const packageName = text.match(/^package\s+([\w.]+)\s*;/m)?.[1]
    if (!packageName) throw new Error('Test source has no package declaration: ' + path.relative(root, file))
    suites.push(packageName + '.' + path.basename(file, '.java'))
    fingerprints.push({ path: path.relative(root, file).replaceAll(path.sep, '/'), sha256: sha256(bytes) })
  }
  return { suites: suites.sort(), fingerprints }
}
const sourceSnapshot = await snapshotTestSources()
const startedAt = new Date()
const invocation = process.platform === 'win32'
  ? spawnSync('cmd.exe', ['/d', '/s', '/c', command], { cwd: root, stdio: 'inherit' })
  : spawnSync('mvn', ['-B', '-pl', 'ddd-agent-sheep-app', '-am', 'package'], { cwd: root, stdio: 'inherit' })
const endedAt = new Date()
if (invocation.error) throw invocation.error
if (invocation.status !== 0) process.exit(invocation.status ?? 1)
const currentSourceSnapshot = await snapshotTestSources()
if (JSON.stringify(currentSourceSnapshot) !== JSON.stringify(sourceSnapshot)) throw new Error('Test sources changed during Maven build; refusing to attest results')
const expectedSuites = sourceSnapshot.suites
const sourceFingerprints = sourceSnapshot.fingerprints
const jarPath = path.join(root, 'ddd-agent-sheep-app', 'target', 'ai-agent-station-study-app.jar')
const reportDirectory = path.join(root, 'ddd-agent-sheep-app', 'target', 'surefire-reports')
const reports = []
for (const file of (await fs.readdir(reportDirectory)).filter(name => /^TEST-.*\.xml$/.test(name)).sort()) {
  const absolute = path.join(reportDirectory, file)
  const xml = await fs.readFile(absolute, 'utf8')
  reports.push({ path: file, sha256: sha256(xml), modifiedAt: (await fs.stat(absolute)).mtime.toISOString(), ...summarizeSurefireXml(xml) })
}
const jarBytes = await fs.readFile(jarPath)
const jarStat = await fs.stat(jarPath)
const attestation = {
  schemaVersion: 1,
  status: 'passed',
  command,
  exitCode: invocation.status,
  startedAt: startedAt.toISOString(),
  endedAt: endedAt.toISOString(),
  jar: { path: path.relative(root, jarPath).replaceAll(path.sep, '/'), sha256: sha256(jarBytes), modifiedAt: jarStat.mtime.toISOString() },
  expectedSuites,
  sourceFingerprints,
  reports,
  totals: reports.reduce((sum, report) => {
    for (const key of Object.keys(sum)) sum[key] += report[key]
    return sum
  }, { tests: 0, failures: 0, errors: 0, skipped: 0 })
}
const verified = verifyBuildAttestation(attestation, {
  jarSha256: attestation.jar.sha256,
  jarModifiedAt: attestation.jar.modifiedAt,
  expectedSuites,
  expectedSourceFingerprints: sourceFingerprints,
  reports
})
if (!verified.valid) throw new Error('Maven passed but build evidence is incomplete: ' + verified.reason)
if (verified.totals.failures || verified.totals.errors) throw new Error('Maven test report contains failures or errors')
const outputDirectory = path.join(root, 'data', 'acceptance')
await fs.mkdir(outputDirectory, { recursive: true })
const stamp = startedAt.toISOString().replace(/[-:.]/g, '').replace('Z', 'Z')
let output = path.join(outputDirectory, 'backend-build-' + stamp + '.json')
for (let suffix = 1; await fs.access(output).then(() => true, () => false); suffix++) output = path.join(outputDirectory, 'backend-build-' + stamp + '-' + suffix + '.json')
await fs.writeFile(output, JSON.stringify(attestation, null, 2), { encoding: 'utf8', flag: 'wx' })
console.log(JSON.stringify({ attestation: path.relative(root, output), jarSha256: attestation.jar.sha256, totals: verified.totals, suites: expectedSuites.length }, null, 2))

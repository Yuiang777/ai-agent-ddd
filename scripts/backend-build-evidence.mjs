import { createHash } from 'node:crypto'

export function sha256(bytes) {
  return createHash('sha256').update(bytes).digest('hex')
}

export function summarizeSurefireXml(xml) {
  const suite = xml.match(/<testsuite\b[^>]*>/)?.[0]
  if (!suite) throw new Error('Invalid Surefire XML')
  const read = key => Number(suite.match(new RegExp('\\b' + key + '="(\\d+)"'))?.[1] || 0)
  return { tests: read('tests'), failures: read('failures'), errors: read('errors'), skipped: read('skipped') }
}

export function verifyBuildAttestation(attestation, { jarSha256, jarModifiedAt, expectedSuites, expectedSourceFingerprints, reports }) {
  const fail = reason => ({ valid: false, reason })
  if (!attestation || attestation.schemaVersion !== 1 || attestation.status !== 'passed') return fail('unsupported or failed build attestation')
  const started = Date.parse(attestation.startedAt)
  const ended = Date.parse(attestation.endedAt)
  if (!Number.isFinite(started) || !Number.isFinite(ended) || ended < started) return fail('invalid build time window')
  if (attestation.exitCode !== 0 || attestation.command !== 'mvn -B -pl ddd-agent-sheep-app -am package') return fail('build command did not complete successfully')
  const inWindow = value => {
    const time = Date.parse(value)
    return Number.isFinite(time) && time >= started - 2000 && time <= ended + 5000
  }
  if (attestation.jar?.sha256 !== jarSha256) return fail('JAR hash does not match')
  const sourceFingerprints = [...(attestation.sourceFingerprints || [])].sort((a, b) => a.path.localeCompare(b.path))
  const expectedSources = [...(expectedSourceFingerprints || [])].sort((a, b) => a.path.localeCompare(b.path))
  if (!expectedSources.length || JSON.stringify(sourceFingerprints) !== JSON.stringify(expectedSources)) return fail('test source fingerprints do not match')
  const expected = [...expectedSuites].sort()
  const claimedSuites = [...(attestation.expectedSuites || [])].sort()
  if (JSON.stringify(expected) !== JSON.stringify(claimedSuites)) return fail('test source suite set does not match')
  const current = new Map(reports.map(report => [report.path, report]))
  const attested = new Map((attestation.reports || []).map(report => [report.path, report]))
  if (current.size !== expected.length || attested.size !== expected.length) return fail('test report count does not match source suite count')
  const totals = { tests: 0, failures: 0, errors: 0, skipped: 0 }
  for (const suite of expected) {
    const file = 'TEST-' + suite + '.xml'
    const report = current.get(file)
    const prior = attested.get(file)
    if (!report || !prior || report.sha256 !== prior.sha256 || !inWindow(report.modifiedAt) || !inWindow(prior.modifiedAt)) return fail('test report is stale or changed: ' + file)
    for (const key of Object.keys(totals)) {
      if (report[key] !== prior[key]) return fail('test result changed: ' + file)
      totals[key] += report[key]
    }
  }
  if (totals.tests < 1 || totals.failures || totals.errors) return fail('test run is empty or failed')
  if (Object.keys(totals).some(key => totals[key] !== attestation.totals?.[key])) return fail('test totals do not match')
  return { valid: true, totals }
}

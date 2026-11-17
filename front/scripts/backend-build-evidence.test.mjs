import test from 'node:test'
import assert from 'node:assert/strict'
import { verifyBuildAttestation } from '../../scripts/backend-build-evidence.mjs'

const startedAt = '2026-10-16T06:00:00.000Z'
const endedAt = '2026-10-16T06:01:00.000Z'
const modifiedAt = '2026-10-16T06:00:30.000Z'
const suiteName = 'cn.apple.test.domain.SampleTest'
const sourceFingerprints = [{ path: 'src/test/java/cn/apple/test/domain/SampleTest.java', sha256: 'c'.repeat(64) }]
const report = { path: 'TEST-' + suiteName + '.xml', sha256: 'a'.repeat(64), modifiedAt, tests: 4, failures: 0, errors: 0, skipped: 1 }
function fixture() {
  return {
    schemaVersion: 1, status: 'passed', command: 'mvn -B -pl ddd-agent-sheep-app -am package', exitCode: 0,
    startedAt, endedAt, jar: { sha256: 'b'.repeat(64), modifiedAt }, expectedSuites: [suiteName], sourceFingerprints,
    reports: [{ ...report }], totals: { tests: 4, failures: 0, errors: 0, skipped: 1 }
  }
}
function verify(attestation = fixture(), overrides = {}) {
  return verifyBuildAttestation(attestation, {
    jarSha256: 'b'.repeat(64), jarModifiedAt: modifiedAt, expectedSuites: [suiteName], expectedSourceFingerprints: sourceFingerprints, reports: [{ ...report }], ...overrides
  })
}

test('accepts a successful build bound to the JAR and every expected Surefire report', () => {
  assert.deepEqual(verify(), { valid: true, totals: { tests: 4, failures: 0, errors: 0, skipped: 1 } })
})

test('rejects a JAR from another build', () => {
  assert.equal(verify(fixture(), { jarSha256: 'c'.repeat(64) }).valid, false)
})

test('accepts reproducible JAR timestamps when the JAR hash and build attestation match', () => {
  const unchangedArchiveTime = '2026-10-16T05:30:00.000Z'
  const attestation = fixture()
  attestation.jar.modifiedAt = unchangedArchiveTime
  assert.equal(verify(attestation, { jarModifiedAt: unchangedArchiveTime }).valid, true)
})

test('rejects test source changes after the attested build', () => {
  assert.equal(verify(fixture(), { expectedSourceFingerprints: [{ ...sourceFingerprints[0], sha256: 'd'.repeat(64) }] }).valid, false)
})

test('rejects missing, stale, or changed test reports', () => {
  assert.equal(verify(fixture(), { reports: [] }).valid, false)
  const stale = fixture()
  stale.reports[0].modifiedAt = '2026-10-16T05:59:00.000Z'
  assert.equal(verify(stale).valid, false)
  assert.equal(verify(fixture(), { reports: [{ ...report, sha256: 'd'.repeat(64) }] }).valid, false)
})

test('rejects failed runs and totals that do not match reports', () => {
  const failed = fixture()
  failed.reports[0].failures = 1
  failed.totals.failures = 1
  assert.equal(verify(failed).valid, false)
  const mismatched = fixture()
  mismatched.totals.tests = 5
  assert.equal(verify(mismatched).valid, false)
})

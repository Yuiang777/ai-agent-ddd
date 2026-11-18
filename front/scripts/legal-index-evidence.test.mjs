import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { createHash } from 'node:crypto'

const evidence = JSON.parse(readFileSync(new URL('../../docs/thesis/legal-index-smoke-2026-11-18.json', import.meta.url), 'utf8'))
const manifestText = readFileSync(new URL('../../docs/thesis/official-law-sources.json', import.meta.url), 'utf8').replace(/\r\n/g, '\n')
const manifest = JSON.parse(manifestText)

test('recorded local-index smoke keeps its source identity and non-evaluation boundary', () => {
  assert.equal(evidence.kind, 'local-index-smoke')
  assert.equal(evidence.currentLawVersionsVerified, false)
  assert.match(evidence.scope, /not application hybrid retrieval/)
  assert.match(evidence.queryProtocol.querySet, /not a held-out evaluation set/)
  assert.equal(evidence.sourceManifestSha256Lf, createHash('sha256').update(manifestText).digest('hex'))
  assert.equal(evidence.model.requestedDimensions, 1536)
  assert.deepEqual(evidence.database.dimensions, [1536])
  assert.equal(evidence.database.stagingRows, 0)
  assert.equal(evidence.database.vectorRows, evidence.database.activeRows)
  assert.equal(evidence.database.activeRows, evidence.ingestion.successfulAttempt.totalChunks)
  assert.equal(evidence.database.activeCatalogVersions, manifest.laws.length)
  assert.equal(evidence.ingestion.successfulAttempt.failedDocuments, 0)
  assert.doesNotMatch(JSON.stringify(evidence), /file:\/\/|authorization|bearer|sk-[a-zA-Z0-9]{16,}/i)
})

test('recorded hits retain reviewed original hashes and active catalog provenance', () => {
  assert.equal(evidence.queries.length, 3)
  assert.equal(new Set(evidence.queries.map(query => query.expectedDocId)).size, 3)
  for (const query of evidence.queries) {
    assert.equal(query.hits.length, evidence.queryProtocol.topK)
    assert.equal(query.expectedLawPresent, query.hits.some(hit => hit.docId === query.expectedDocId))
    for (const hit of query.hits) {
      const law = manifest.laws.find(item => hit.lawName.startsWith(item.title + '_'))
      assert.ok(law)
      assert.equal(hit.originalObjectSha256, law.textSha256)
      assert.equal(hit.activeCatalogVersionMatched, true)
      assert.match(hit.normalizedSourceChecksum, /^[a-f0-9]{64}$/)
      assert.match(hit.documentVersion, /^[a-f0-9]{32}$/)
      assert.match(hit.articleNo, /^第.+条$/u)
    }
  }
})

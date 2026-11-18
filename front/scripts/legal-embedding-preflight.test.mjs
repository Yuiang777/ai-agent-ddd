import test from 'node:test'
import assert from 'node:assert/strict'
import { checkLegalEmbedding } from '../../scripts/check-legal-embedding.mjs'

function fixture(columnType = 'vector(3)', payload = { data: [{ index: 0, embedding: [0.1, 0.2, 0.3] }] }) {
  const commands = []
  const requests = []
  return { commands, requests, dependencies: {
    execute: async (...args) => { commands.push(args); return { stdout: columnType + '\n' } },
    fetch: async (...args) => { requests.push(args); return { ok: true, json: async () => payload } }
  } }
}

test('preflight only reads column metadata and sends a synthetic embedding probe', async () => {
  const state = fixture()
  const result = await checkLegalEmbedding({ model: 'test-embedding', apiKey: 'private-test-token' }, state.dependencies)
  assert.equal(result.dimensionsMatch, true)
  assert.equal(result.modelDimensions, 3)
  assert.equal(result.tableDimensions, 3)
  assert.equal(state.commands.length, 1)
  const [command, args, options] = state.commands[0]
  assert.equal(command, 'docker')
  assert.equal(args.at(-2), '-c')
  assert.match(args.at(-1), /^SELECT /)
  assert.doesNotMatch(args.at(-1), /\b(INSERT|UPDATE|DELETE|ALTER|CREATE|DROP)\b/i)
  assert.equal(options.windowsHide, true)
  const [endpoint, request] = state.requests[0]
  assert.equal(endpoint.href, 'http://127.0.0.1:11434/v1/embeddings')
  assert.equal(request.redirect, 'error')
  assert.equal(request.headers.Authorization, 'Bearer private-test-token')
  assert.equal(JSON.parse(request.body).input, '法律知识库嵌入维度检查')
  assert.doesNotMatch(JSON.stringify(result), /private-test-token|Authorization/)
  assert.equal(Object.hasOwn(result, 'embedding'), false)
})

test('preflight reports mismatch without truncating vectors or mutating the table', async () => {
  const state = fixture('vector(1536)')
  const result = await checkLegalEmbedding({ model: 'test-embedding' }, state.dependencies)
  assert.equal(result.dimensionsMatch, false)
  assert.equal(result.tableDimensions, 1536)
  assert.equal(result.modelDimensions, 3)
  assert.equal(state.commands.length, 1)
})

test('explicit dimensions are forwarded without local truncation', async () => {
  const state = fixture()
  const result = await checkLegalEmbedding({ model: 'test', dimensions: 3 }, state.dependencies)
  assert.equal(JSON.parse(state.requests[0][1].body).dimensions, 3)
  assert.equal(result.requestedDimensions, 3)
  assert.equal(result.dimensionsMatch, true)

  const ignored = await checkLegalEmbedding({ model: 'test', dimensions: 2 }, state.dependencies)
  assert.equal(ignored.dimensionsMatch, false)
  assert.equal(ignored.modelDimensions, 3)
})

test('invalid explicit dimensions fail before database or provider access', async () => {
  for (const dimensions of [0, -1, 1.5, NaN, Infinity, null, '1536', Number.MAX_SAFE_INTEGER + 1]) {
    const state = fixture()
    await assert.rejects(checkLegalEmbedding({ model: 'test', dimensions }, state.dependencies), /positive safe integer/)
    assert.equal(state.commands.length, 0)
    assert.equal(state.requests.length, 0)
  }
})

test('missing or unsupported column fails before contacting the provider', async () => {
  for (const columnType of ['', 'vector', 'halfvec(3)', 'vector(0)', 'vector(3)\nvector(4)']) {
    const state = fixture(columnType)
    await assert.rejects(checkLegalEmbedding({ model: 'test' }, state.dependencies), /fixed-dimension/)
    assert.equal(state.requests.length, 0)
  }
})

test('invalid identifiers and unsafe endpoints never execute commands or requests', async () => {
  for (const settings of [{ table: 'law; DROP TABLE law' }, { container: '--help' },
    { database: 'bad;db' }, { username: '--user' }, { model: ' ' },
    { baseUrl: 'not a url' }, { baseUrl: 'http://example.com/v1' },
    { baseUrl: 'https://user:secret@example.com/v1' }, { baseUrl: 'https://example.com/v1?key=secret' }]) {
    const state = fixture()
    await assert.rejects(checkLegalEmbedding({ model: 'test', ...settings }, state.dependencies))
    assert.equal(state.commands.length, 0)
    assert.equal(state.requests.length, 0)
  }
})

test('malformed, base64, nonfinite and multiple embeddings fail closed', async () => {
  for (const payload of [null, {}, { data: [] },
    { data: [{ index: 0, embedding: [] }] }, { data: [{ index: 1, embedding: [1] }] },
    { data: [{ index: 0, embedding: 'base64' }] }, { data: [{ index: 0, embedding: [NaN] }] },
    { data: [{ index: 0, embedding: [Infinity] }] }, { data: [{ index: 0, embedding: ['1'] }] },
    { data: [{ index: 0, embedding: [1] }, { index: 1, embedding: [1] }] }]) {
    const state = fixture('vector(3)', payload)
    await assert.rejects(checkLegalEmbedding({ model: 'test' }, state.dependencies), /exactly one/)
  }
})

test('provider and database failures never expose raw errors or credentials', async () => {
  const secret = 'sensitive-upstream-body'
  for (const failure of ['execute', 'fetch']) {
    const state = fixture()
    state.dependencies[failure] = async () => { throw new Error(secret) }
    await assert.rejects(checkLegalEmbedding({ model: 'test' }, state.dependencies), error => {
      assert.ok(!error.message.includes(secret))
      return true
    })
  }
  const state = fixture()
  state.dependencies.fetch = async () => ({ ok: false, json: async () => { throw new Error(secret) } })
  await assert.rejects(checkLegalEmbedding({ model: 'test' }, state.dependencies), /response and credentials are omitted/)
})

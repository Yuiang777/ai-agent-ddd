import assert from 'node:assert/strict'
import test from 'node:test'
import { readSseEvents } from '../src/api/sse.js'
function chunks(values) { return new ReadableStream({ start(controller) { for (const value of values) controller.enqueue(value); controller.close() } }) }
const encode = text => new TextEncoder().encode(text)
test('UTF-8 text survives single-byte network chunks, CRLF boundaries and EOF without a blank line', async () => {
  const bytes = encode('event: NODE_OUTPUT\r\ndata: 中文正文\r\n\r\nevent: WORKFLOW_COMPLETED\r\ndata: 完成')
  const events = []
  await readSseEvents(chunks([...bytes].map(value => Uint8Array.of(value))), (event, data) => events.push({ event, data }))
  assert.deepEqual(events, [{ event: 'NODE_OUTPUT', data: '中文正文' }, { event: 'WORKFLOW_COMPLETED', data: '完成' }])
})
test('comments, multiline data and one optional space are parsed without trimming content', async () => {
  const events = []
  await readSseEvents(chunks([encode(': keepalive\ndata:  indented\ndata:second\n\ndata: next\n\n')]), (event, data) => events.push({ event, data }))
  assert.deepEqual(events, [{ event: 'message', data: ' indented\nsecond' }, { event: 'message', data: 'next' }])
})
test('failed event handlers cancel the stream and release its reader', async () => {
  let cancelled = false
  const stream = new ReadableStream({ start(controller) { controller.enqueue(encode('event: WORKFLOW_FAILED\ndata: failed\n\n')) }, cancel() { cancelled = true } })
  await assert.rejects(readSseEvents(stream, () => { throw new Error('failure event') }), /failure event/)
  assert.equal(cancelled, true)
  assert.equal(stream.locked, false)
})
test('a new request never inherits an event name or a trailing fragment', async () => {
  const events = []
  await readSseEvents(chunks([encode('event: old')]), (event, data) => events.push({ event, data }))
  await readSseEvents(chunks([encode('data: fresh\n\n')]), (event, data) => events.push({ event, data }))
  assert.deepEqual(events, [{ event: 'message', data: 'fresh' }])
})

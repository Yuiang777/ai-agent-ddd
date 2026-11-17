/** Decode one SSE response; state is scoped to the request, including UTF-8 and EOF handling. */
export async function readSseEvents(stream, onEvent) {
  const reader = stream.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''
  let event = ''
  let data = []
  let finished = false
  const flush = () => {
    if (data.length) onEvent(event || 'message', data.join('\n'))
    event = ''; data = []
  }
  const line = value => {
    if (!value) { flush(); return }
    if (value.startsWith(':')) return
    const colon = value.indexOf(':')
    const field = colon < 0 ? value : value.slice(0, colon)
    let text = colon < 0 ? '' : value.slice(colon + 1)
    if (text.startsWith(' ')) text = text.slice(1)
    if (field === 'event') event = text
    if (field === 'data') data.push(text)
  }
  const consume = final => {
    // A CR may be split from the LF in a following network chunk.
    for (;;) {
      const index = buffer.search(/[\r\n]/)
      if (index < 0) break
      if (buffer[index] === '\r' && index === buffer.length - 1 && !final) break
      const count = buffer[index] === '\r' && buffer[index + 1] === '\n' ? 2 : 1
      const next = buffer.slice(0, index)
      buffer = buffer.slice(index + count)
      line(next)
    }
    if (final) { if (buffer) line(buffer); buffer = ''; flush() }
  }
  try {
    for (;;) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      consume(false)
    }
    buffer += decoder.decode()
    consume(true)
    finished = true
  } finally {
    if (!finished) await reader.cancel().catch(() => {})
    reader.releaseLock()
  }
}

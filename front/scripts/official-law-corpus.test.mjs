import test from 'node:test'
import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { readFileSync } from 'node:fs'
import { basename } from 'node:path'

const manifest = JSON.parse(readFileSync(new URL('../../docs/thesis/official-law-sources.json', import.meta.url), 'utf8'))
const digits = '零一二三四五六七八九'

function chinese(number) {
  if (number < 10) return digits[number]
  if (number < 20) return '十' + (number % 10 ? digits[number % 10] : '')
  return digits[Math.floor(number / 10)] + '十' + (number % 10 ? digits[number % 10] : '')
}

for (const law of manifest.laws) {
  test(law.title + ' retains a complete, hashed official-text snapshot', () => {
    assert.match(law.path, /^data\/legal\/raw\//)
    assert.match(basename(law.path), /^[^/\\]+_[0-9]{8}\.md$/u)
    assert.match(law.officialUrl, /^https:\/\/(www\.spp\.gov\.cn|www\.gjbmj\.gov\.cn)\//)
    assert.match(law.sourceHtmlSha256, /^[a-f0-9]{64}$/)
    assert.equal(law.currentVersionVerified, false)
    const text = readFileSync(new URL('../../' + law.path, import.meta.url), 'utf8').replace(/\r\n/g, '\n')
    assert.equal(createHash('sha256').update(text, 'utf8').digest('hex'), law.textSha256)
    assert.equal(text.split('\n')[0], law.title)
    const articles = [...text.matchAll(/^第([一二三四五六七八九十百零]+)条(?=[\s　]|$)/gmu)]
      .map(match => match[1])
    assert.deepEqual(articles, Array.from({ length: law.articleCount }, (_, index) => chinese(index + 1)))
    const [year, month, day] = law.effectiveFrom.split('-').map(Number)
    assert.ok(text.slice(-120).includes('本法自' + year + '年' + month + '月' + day + '日起施行'))
  })
}

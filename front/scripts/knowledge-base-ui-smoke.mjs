import assert from 'node:assert/strict'
import { fileURLToPath } from 'node:url'
import { preview } from 'vite'
import { chromium } from 'playwright'

const root = fileURLToPath(new URL('..', import.meta.url))
const server = await preview({ root, configFile: false, preview: { host: '127.0.0.1', port: 0, open: false } })
let browser
try {
  browser = await chromium.launch({ channel: process.env.PLAYWRIGHT_CHANNEL || 'msedge', headless: true })
  const page = await browser.newPage()
  const errors = []
  page.on('pageerror', error => errors.push(error.message))
  await page.route('**/api/**', route => route.fulfill({ json: { success: true,
    data: route.request().url().endsWith('/context') ? { role: 'LAWYER' } : [] } }))
  await page.route('**/api/legal/documents/upload-capabilities', route => route.fulfill({ json: { success: true,
    data: { enabled: false, reason: '测试环境不允许上传', maxFileBytes: 0, allowedExtensions: [] } } }))
  let stats = { totalDocuments: 3, successDocuments: 2, failedDocuments: 1, totalChunks: 50 }
  await page.route('**/api/legal/documents/stats', route => route.fulfill({ json: { success: true, data: stats } }))
  const origin = 'http://127.0.0.1:' + server.httpServer.address().port
  await page.goto(origin + '/knowledge-base')
  const cards = [['暂存目录文档数', '3'], ['解析成功', '2'], ['解析分片', '50'], ['解析失败', '1']]
  for (const [label, value] of cards) {
    const card = page.getByText(label, { exact: true }).locator('..')
    await card.getByText(value, { exact: true }).waitFor()
  }
  assert.equal(await page.getByText('已摄取文档', { exact: true }).count(), 0)
  assert.equal(await page.getByText(/不代表已完成向量入库或可以检索/).count(), 1)
  assert.equal(await page.getByRole('button', { name: '选择文档', exact: true }).isDisabled(), true)
  stats = { ...stats, successDocuments: '2' }
  await page.getByRole('button', { name: '刷新状态', exact: true }).click()
  await page.getByText(/知识库统计结果格式异常/).waitFor()
  for (const [label] of cards) {
    const card = page.getByText(label, { exact: true }).locator('..')
    assert.equal(await card.getByText('—', { exact: true }).count(), 1)
  }
  assert.deepEqual(errors, [])
  console.log('PASS mocked parsing statistics never imply indexed documents; invalid statistics remain unknown')
} finally {
  await browser?.close()
  await server.close()
}

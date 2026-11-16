import assert from 'node:assert/strict'
import { fileURLToPath } from 'node:url'
import { preview } from 'vite'
import { chromium } from 'playwright'

const root = fileURLToPath(new URL('..', import.meta.url))
const server = await preview({ root, configFile: false, preview: { host: '127.0.0.1', port: 0, open: false } })
let browser
try {
  browser = await chromium.launch({ channel: process.env.PLAYWRIGHT_CHANNEL || 'msedge', headless: true })
  const page = await browser.newPage({ viewport: { width: 390, height: 844 } })
  const errors = []
  page.on('pageerror', error => errors.push(error.message))
  await page.route('**/api/**', route => route.fulfill({ json: { success: true, data: route.request().url().endsWith('/context') ? { role: 'LAWYER' } : [] } }))
  await page.route('**/api/governance/readiness', route => route.fulfill({ json: { success: true, data: { capabilities: { model_runtime: { status: 'CONFIGURED_UNVERIFIED' } } } } }))
  await page.route('**/api/chat', route => route.fulfill({ json: { success: true, data: { content: '仅用于测试的模型回复' } } }))
  const origin = 'http://127.0.0.1:' + server.httpServer.address().port
  await page.goto(origin + '/chat')
  await page.getByLabel('事实与法律问题', { exact: false }).fill('测试法律问题')
  await page.getByRole('button', { name: '发送问题', exact: true }).click()
  await page.getByText('仅用于测试的模型回复', { exact: true }).waitFor()
  assert.equal(await page.getByText('来源未核验 · 仅供内部讨论', { exact: true }).count(), 1)
  assert.equal((await page.locator('main').innerText()).includes('当前会话：local-'), false)
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true)
  assert.deepEqual(errors, [])
  console.log('PASS consultation draft reply is explicitly unverified and usable on narrow screens')
  console.log('Mock response only; this does not establish legal accuracy or citation support.')
} finally {
  await browser?.close()
  await new Promise(resolve => server.httpServer.close(resolve))
}
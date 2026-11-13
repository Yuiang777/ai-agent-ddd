/** UI failure simulations. These responses are deliberately mocked and are not model validation. */
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { chromium } from 'playwright'
const origin = process.env.ACCEPTANCE_ORIGIN || 'http://127.0.0.1:4173'
assert.equal(new URL(origin).hostname, '127.0.0.1')
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..')
const output = path.join(root, 'data', 'acceptance', `ui-failures-${Date.now()}`)
await fs.mkdir(output, { recursive: true })
const buildIdentity = await (await fetch(origin + '/__acceptance/build')).json()
assert.match(buildIdentity.jarSha256, /^[a-f0-9]{64}$/)
const browser = await chromium.launch({ headless: true, channel: process.env.PLAYWRIGHT_CHANNEL || 'msedge' })
const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } })
const errors = []
page.on('pageerror', error => errors.push(error.message))
const steps = []
const pass = name => { steps.push(name); console.log(`PASS ${name}`) }
try {
  await page.route('**/api/governance/readiness', route => route.fulfill({ json: { success: true, data: { capabilities: { model_runtime: { status: 'CONFIGURED_UNVERIFIED' } } } } }))
  let chatCalls = 0
  await page.route('**/api/chat', route => {
    chatCalls++
    return route.fulfill({ json: chatCalls === 1 ? { success: false, message: '模拟模型失败' } : { success: true, data: { content: '【模拟回复】只验证前端行为，不构成法律意见。' } } })
  })
  await page.goto(`${origin}/chat`)
  await page.getByLabel('事实与法律问题', { exact: false }).fill('第一会话的模拟问题')
  await page.getByRole('button', { name: '发送问题', exact: true }).click()
  await page.getByText(/模拟模型失败。/).waitFor()
  assert.equal(await page.getByLabel('事实与法律问题', { exact: false }).inputValue(), '第一会话的模拟问题')
  assert.equal(await page.getByText('【模拟回复】只验证前端行为，不构成法律意见。', { exact: true }).count(), 0)
  await page.getByRole('button', { name: '发送问题', exact: true }).click()
  await page.getByText('【模拟回复】只验证前端行为，不构成法律意见。', { exact: true }).waitFor()
  await page.getByRole('button', { name: '新建咨询', exact: true }).click()
  assert.equal(await page.getByText('【模拟回复】只验证前端行为，不构成法律意见。', { exact: true }).count(), 0)
  await page.getByLabel('事实与法律问题', { exact: false }).fill('第二会话的未发送草稿')
  await page.getByRole('button', { name: '第一会话的模拟问题', exact: true }).click()
  await page.getByText('【模拟回复】只验证前端行为，不构成法律意见。', { exact: true }).waitFor()
  await page.getByRole('button', { name: '新建法律咨询', exact: true }).click()
  assert.equal(await page.getByLabel('事实与法律问题', { exact: false }).inputValue(), '第二会话的未发送草稿')
  pass('Chat business failure restores input; successful retry and per-session drafts')

  await page.route('**/api/workflows/run/stream', route => route.fulfill({ contentType: 'text/event-stream', body: 'event: NODE_OUTPUT\ndata: {"runId":"SIMULATED","data":{"text":"不完整的模拟报告"}}\n\n' }))
  await page.goto(`${origin}/workflow`)
  await page.getByRole('button', { name: '实时执行', exact: true }).click()
  await page.getByText(/工作流连接已结束，但未返回完成事件/).waitFor()
  assert.equal(await page.getByText('不完整的模拟报告', { exact: true }).count(), 0)
  assert.equal(await page.getByRole('button', { name: '确认登记为报告新版本', exact: true }).count(), 0)
  pass('Truncated SSE cannot be registered as a completed report')

  await page.unroute('**/api/workflows/run/stream')
  await page.route('**/api/workflows/run/stream', route => route.fulfill({ contentType: 'text/event-stream', body: 'event: WORKFLOW_COMPLETED\ndata: {"data":{"status":"SUCCESS","finalOutput":"SIMULATED-DRAFT"}}\n\n' + 'event: WORKFLOW_COMPLETED\ndata: {"data":{"status":"INVALID"}}\n\n' }))
  await page.getByRole('button', { name: '实时执行', exact: true }).click()
  await page.getByText(/工作流未返回有效结束状态/).waitFor()
  assert.equal(await page.getByText('SIMULATED-DRAFT', { exact: true }).count(), 0)
  assert.equal(await page.getByRole('button', { name: '确认登记为报告新版本', exact: true }).count(), 0)
  pass('A later invalid SSE event clears an earlier completed draft')
  let completeWorkflow = false
  await page.route('**/api/workflows/run', route => route.fulfill({ json: { success: true, data: {
    runId: 'SIMULATED-REGISTRATION', finalOutput: '【模拟报告】用于登记并发验证，不构成法律意见。',
    ...(completeWorkflow ? { status: 'SUCCESS' } : {})
  } } }))
  await page.goto(`${origin}/workflow`)
  await page.getByRole('button', { name: '同步执行', exact: true }).click()
  await page.getByText(/工作流未返回有效结束状态/).waitFor()
  assert.equal(await page.getByRole('button', { name: '确认登记为报告新版本', exact: true }).count(), 0)
  pass('A response without terminal status cannot become a completed report')
  const post = async (url, data) => {
    const result = await (await page.request.post(origin + url, { data })).json()
    assert.equal(result.success, true, result.message)
    return result.data
  }
  let concurrent = await post('/api/legal/matters', { title: `登记并发验收-${Date.now()}`, clientReference: 'SIMULATED-CONCURRENT' })
  const concurrentId = concurrent.matter.id
  for (const target of ['CONFLICT_CHECK', 'ENGAGEMENT_PENDING', 'MATERIAL_PENDING', 'ANALYSIS']) {
    concurrent = await post(`/api/legal/matters/${concurrentId}/transitions`, { target, reason: '模拟受理核验', reference: 'SIMULATED-INTAKE', expectedVersion: concurrent.matter.version })
  }
  completeWorkflow = true
  await page.getByRole('button', { name: '同步执行', exact: true }).click()
  const selection = page.getByLabel('目标法律事项', { exact: true })
  await selection.click()
  await page.getByRole('option', { name: new RegExp(concurrent.matter.title) }).click()
  await post(`/api/legal/matters/${concurrentId}/artifacts`, { content: '其他用户同时登记的测试报告', expectedVersion: concurrent.matter.version })
  await page.getByRole('button', { name: '确认登记为报告新版本', exact: true }).click()
  await page.getByText(/目标事项在列表加载后已变更/).waitFor()
  let after = await (await page.request.get(`${origin}/api/legal/matters/${concurrentId}`)).json()
  assert.equal(after.data.matter.artifacts.length, 1)
  await page.getByRole('button', { name: '刷新事项', exact: true }).click()
  await page.getByRole('button', { name: '确认登记为报告新版本', exact: true }).click()
  await page.getByText(/报告 V2 已保存到事项/).waitFor()
  after = await (await page.request.get(`${origin}/api/legal/matters/${concurrentId}`)).json()
  assert.equal(after.data.matter.artifacts.length, 2)
  assert.equal(after.data.matter.artifacts[1].workflowRunId, 'SIMULATED-REGISTRATION')
  await page.getByRole('button', { name: '打开事项', exact: true }).click()
  assert.equal(new URL(page.url()).searchParams.get('matter'), concurrentId)
  await page.getByRole('dialog', { name: new RegExp(concurrent.matter.title) }).waitFor()
  pass('Concurrent report change blocks registration; explicit refresh permits a new real version and deep link')
  await page.unroute('**/api/workflows/run')
  await page.getByRole('tab', { name: '报告与版本', exact: true }).click()
  const preservedDraft = '【模拟提交冲突】提交失败后必须保留的报告正文。'
  await page.getByLabel('报告正文', { exact: true }).fill(preservedDraft)
  const artifactRoute = '**/api/legal/matters/' + concurrentId + '/artifacts'
  await page.route(artifactRoute, route => route.fulfill({ json: { success: false, message: 'MATTER_VERSION_CONFLICT' } }))
  await page.getByRole('button', { name: '发布为新版本', exact: true }).click()
  await page.getByText(/事项已被其他操作更新，请刷新后核对最新内容再提交/).waitFor()
  assert.equal(await page.getByLabel('报告正文', { exact: true }).inputValue(), preservedDraft)
  const unchanged = await (await page.request.get(origin + '/api/legal/matters/' + concurrentId)).json()
  assert.equal(unchanged.data.matter.artifacts.length, 2)
  await page.unroute(artifactRoute)
  await page.getByRole('button', { name: '发布为新版本', exact: true }).click()
  await page.getByText('报告新版本已登记，需要重新复核。', { exact: true }).waitFor()
  assert.equal(await page.getByLabel('报告正文', { exact: true }).inputValue(), '')
  const recovered = await (await page.request.get(origin + '/api/legal/matters/' + concurrentId)).json()
  assert.equal(recovered.data.matter.artifacts.length, 3)
  assert.equal(recovered.data.matter.artifacts.at(-1).content, preservedDraft)
  pass('Matter conflict shows actionable message, preserves report draft and permits a successful retry')


  await page.route('**/api/legal/documents/upload-capabilities', route => route.fulfill({ json: { success: true, data: { enabled: true, status: 'CONFIGURED_UNVERIFIED', reason: '模拟已启用能力', maxFileBytes: 1048576, maxRequestBytes: 2097152, allowedExtensions: ['txt'] } } }))
  await page.route('**/api/legal/documents/upload', route => route.fulfill({ json: { success: false, message: '模拟目录登记失败' } }))
  await page.goto(`${origin}/knowledge-base`)
  await page.locator('input[type=file]').setInputFiles({ name: 'simulated.txt', mimeType: 'text/plain', buffer: Buffer.from('test text') })
  await page.getByRole('button', { name: '确认上传', exact: true }).click()
  await page.getByText(/模拟目录登记失败。/).waitFor()
  assert.equal(await page.getByText('已存储，待入库', { exact: true }).count(), 0)
  await page.getByText(/已选择：simulated.txt/).waitFor()
  pass('Knowledge upload HTTP 200 business error does not create a success row')
  await page.unroute('**/api/governance/readiness')
  await page.unroute('**/api/chat')
  await page.unroute('**/api/workflows/run/stream')
  await page.unroute('**/api/legal/documents/upload-capabilities')
  await page.unroute('**/api/legal/documents/upload')
  await page.goto(`${origin}/contract-review`)
  await page.getByText('当前环境未启用模型，合同自动审查不可用。你仍可在法律事项中登记材料和人工报告。', { exact: true }).waitFor()
  assert.equal(await page.getByRole('button', { name: '开始审查', exact: true }).isDisabled(), true)
  pass('Real offline model status disables contract analysis')
  await page.route('**/api/governance/readiness', route => route.fulfill({ json: { success: true, data: { capabilities: {} } } }))
  for (const [route, action] of [['/chat', '发送问题'], ['/contract-review', '开始审查'], ['/agent-studio', '运行服务测试'], ['/tool-agent', '调用工具 Agent'], ['/multimodal', '提交任务']]) {
    await page.goto(origin + route, { waitUntil: 'networkidle' })
    assert.equal(await page.getByRole('button', { name: action, exact: true }).isDisabled(), true)
  }
  pass('Missing model capability cannot silently authorize five model entry points')
  await page.unroute('**/api/governance/readiness')
  await page.setViewportSize({ width: 390, height: 844 })
  for (const route of ['/chat', '/contract-review', '/knowledge-base', '/audit', '/agent-studio', '/settings', '/matters', '/tool-agent', '/multimodal', '/governance', '/dashboard', '/workflow', '/risk-inspection', '/law-library', '/templates']) {
    await page.goto(origin + route)
    await page.locator('h5').first().waitFor()
    await page.waitForFunction(() => document.documentElement.scrollWidth === innerWidth)
  }
  pass('Fifteen narrow-screen routes render without root horizontal overflow')
  assert.deepEqual(errors, [])
  const finalBuildResponse = await fetch(origin + '/__acceptance/build')
  assert.equal(finalBuildResponse.status, 200, 'Acceptance artifacts changed during verification')
  assert.deepEqual(await finalBuildResponse.json(), buildIdentity, 'Acceptance build changed during verification')
  await fs.writeFile(path.join(output, 'result.json'), JSON.stringify({ success: true, buildIdentity, simulatedResponses: true, steps, pageErrors: errors }, null, 2))
  console.log(`Evidence: ${output}`)
} catch (error) {
  await page.screenshot({ path: path.join(output, 'failure.png'), fullPage: true }).catch(() => {})
  await fs.writeFile(path.join(output, 'result.json'), JSON.stringify({ success: false, simulatedResponses: true, steps, error: error.stack, pageErrors: errors }, null, 2))
  throw error
} finally { await browser.close() }

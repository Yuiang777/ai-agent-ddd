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
  const origin = 'http://127.0.0.1:' + server.httpServer.address().port
  await page.goto(origin + '/workflow')
  await page.locator('input[type=file]').waitFor({ state: 'attached' })
  assert.equal(await page.getByText('检索：未执行', { exact: true }).count(), 1)
  for (const mode of ['同步执行', '实时执行']) {
    for (const [retrievalStatus, expected] of [
      ['HEALTHY', '正常'], ['DEGRADED', '降级'], ['INSUFFICIENT', '证据不足'], ['UNAVAILABLE', '不可用'],
      [undefined, '状态未知'], ['UNRECOGNIZED', '状态未知'], [{ status: 'HEALTHY' }, '状态未知']
    ]) {
      const endpoint = mode === '同步执行' ? '**/api/workflows/run' : '**/api/workflows/run/stream'
      const data = { status: 'REVIEW_REQUIRED', finalOutput: 'RETRIEVAL_REPORT', variables: { retrievalStatus } }
      await page.route(endpoint, route => route.fulfill(mode === '同步执行'
        ? { json: { success: true, data } }
        : { contentType: 'text/event-stream', body: 'event: NODE_COMPLETED\ndata: ' +
          JSON.stringify({ nodeId: 'retrieval', data: { retrievalStatus } }) + '\n\n' +
          'event: WORKFLOW_COMPLETED\ndata: ' + JSON.stringify({ data }) + '\n\n' }))
      await page.getByRole('button', { name: mode, exact: true }).click()
      await page.getByRole('button', { name: '确认登记为报告新版本', exact: true }).waitFor()
      const statusChip = page.getByText('检索：' + expected, { exact: true })
      assert.equal(await statusChip.count(), 1)
      assert.match(await statusChip.locator('..').getAttribute('title'), /不代表引用或法律结论已经核验/)
      assert.deepEqual(errors, [])
      await page.unroute(endpoint)
    }
  }
  console.log('PASS retrieval labels are localized and missing or unrecognized status stays unknown in both modes')
  for (const status of ['BLOCKED', 'FAILED']) {
    const publishableStatus = status === 'BLOCKED' ? 'SUCCESS' : 'REVIEW_REQUIRED'
    await page.route('**/api/workflows/run', route => route.fulfill({ json: { success: true, data: {
      runId: 'VERIFIED-SYNC', status: publishableStatus, finalOutput: 'VISIBLE_REPORT', risks: [{ title: 'VISIBLE_RISK' }],
      citations: [{ title: 'VISIBLE_CITATION', source: 'fixture-source', excerpt: 'fixture-quote' }], variables: { casePosture: 'LITIGATION', litigationStrategy: 'VISIBLE_STRATEGY' }
    } } }))
    await page.getByRole('button', { name: '同步执行', exact: true }).click()
    for (const marker of ['VISIBLE_REPORT', 'VISIBLE_RISK', 'VISIBLE_CITATION', 'VISIBLE_STRATEGY']) {
      await page.getByText(marker, { exact: true }).waitFor()
    }
    await page.getByRole('button', { name: '确认登记为报告新版本', exact: true }).waitFor()
    await page.unroute('**/api/workflows/run')
    assert.equal(await page.getByText("\u8fd0\u884c\u7f16\u53f7\u7f3a\u5931\uff0c\u672c\u6b21\u62a5\u544a\u4e0d\u80fd\u767b\u8bb0", { exact: false }).count(), 0)
    console.log('PASS mocked ' + publishableStatus + ' result sections and registration are visible')
    await page.route('**/api/workflows/run', route => route.fulfill({ json: { success: true, data: {
      status, finalOutput: 'LEAK_REPORT', risks: [{ title: 'LEAK_RISK' }],
      citations: [{ title: 'LEAK_CITATION' }], variables: { casePosture: 'LITIGATION', litigationStrategy: 'LEAK_STRATEGY' }
    } } }))
    await page.getByRole('button', { name: '同步执行', exact: true }).click()
    await page.getByText(status === 'BLOCKED' ? '受理已暂停，本次未形成报告。' : '执行失败，本次未形成报告。', { exact: true }).waitFor()
    if (status === 'BLOCKED') await page.getByText(/未提供具体受理原因，请联系审核人员核查/).waitFor()
    assert.equal((await page.locator('main').innerText()).includes('LEAK_'), false)
    assert.equal((await page.locator('main').innerText()).includes('VISIBLE_'), false)
    assert.equal(await page.getByRole('button', { name: '确认登记为报告新版本', exact: true }).count(), 0)
    await page.unroute('**/api/workflows/run')
    console.log('PASS mocked ' + status + ' result is hidden and cannot be registered')
  }
  for (const mode of ['同步执行', '实时执行']) {
    for (const reviewReasons of [undefined, [], ['模拟复核原因'], [{ unexpected: true }], [{ unexpected: true }, '模拟复核原因']]) {
      const endpoint = mode === '同步执行' ? '**/api/workflows/run' : '**/api/workflows/run/stream'
      const result = { runId: 'VERIFIED-SYNC', status: 'REVIEW_REQUIRED', finalOutput: 'REVIEW_REPORT', reviewReasons, variables: { reviewReasons } }
      await page.route(endpoint, route => route.fulfill(mode === '同步执行'
        ? { json: { success: true, data: result } }
        : { contentType: 'text/event-stream', body: 'event: WORKFLOW_COMPLETED\ndata: ' + JSON.stringify({ runId: 'VERIFIED-STREAM', data: result }) + '\n\n' }))
      await page.getByRole('button', { name: mode, exact: true }).click()
      await page.getByText('REVIEW_REPORT', { exact: true }).waitFor()
      assert.equal(await page.locator('.MuiAlert-standardError').count(), 0)
      await page.getByRole('button', { name: '确认登记为报告新版本', exact: true }).waitFor()
      assert.equal(await page.getByText('本次结果必须人工复核后方可对外使用', { exact: true }).count(), 1)
      assert.equal((await page.locator('main').innerText()).includes('未触发强制人工复核规则'), false)
      const warning = page.locator('.MuiAlert-message').filter({ hasText: '本次结果必须人工复核后方可对外使用' })
      assert.equal((await warning.innerText()).includes(reviewReasons?.find(reason => typeof reason === 'string' && reason.trim()) || '系统已将本次结果标记为待人工复核'), true)
      assert.deepEqual(errors, [])
      await page.unroute(endpoint)
    }
  }
  console.log('PASS review-required status always displays a warning in sync and stream modes, even without reasons')
  await page.route('**/api/workflows/run/stream', route => route.fulfill({ contentType: 'text/event-stream',
    body: 'event: NODE_STARTED\ndata: {"nodeId":"report"}\n\n' +
      'event: WORKFLOW_COMPLETED\ndata: {"data":{"status":"SUCCESS","finalOutput":"LEAK_DRAFT"}}\n\n' +
      'event: WORKFLOW_COMPLETED\ndata: {"data":{"status":"INVALID"}}\n\n' }))
  await page.getByRole('button', { name: '实时执行', exact: true }).click()
  await page.getByText(/工作流未返回有效结束状态/).waitFor()
  await page.locator('.MuiChip-label').filter({ hasText: /^执行失败$/ }).waitFor()
  assert.equal((await page.locator('main').innerText()).includes('LEAK_'), false)
  assert.equal(await page.getByRole('button', { name: '确认登记为报告新版本', exact: true }).count(), 0)
  assert.deepEqual(errors, [])
  console.log('PASS mocked late SSE failure retracts the earlier draft; no page errors')
  for (const message of ['STREAM_FAILURE', { detail: 'untrusted error object' }]) {
    await page.unroute('**/api/workflows/run/stream')
    await page.route('**/api/workflows/run/stream', route => route.fulfill({ contentType: 'text/event-stream',
      body: 'event: WORKFLOW_FAILED\ndata: ' + JSON.stringify({ message }) + '\n\n' +
        'event: WORKFLOW_COMPLETED\ndata: ' + JSON.stringify({ data: {
          status: 'SUCCESS', finalOutput: 'LEAK_AFTER_FAILURE', riskItems: [{ title: 'LEAK_RISK' }],
          citationItems: [{ title: 'LEAK_CITATION' }]
        } }) + '\n\n' }))
    await page.getByRole('button', { name: '实时执行', exact: true }).click()
    await page.locator('.MuiAlert-message').filter({ hasText: typeof message === 'string' ? message : '工作流失败' }).waitFor()
    await page.locator('.MuiChip-label').filter({ hasText: /^执行失败$/ }).waitFor()
    assert.equal((await page.locator('main').innerText()).includes('LEAK_'), false)
    assert.equal(await page.getByRole('button', { name: '确认登记为报告新版本', exact: true }).count(), 0)
    assert.deepEqual(errors, [])
  }
  console.log('PASS workflow failure stops later completion events and malformed messages cannot crash the page')
  await page.unroute('**/api/workflows/run/stream')
  await page.route('**/api/workflows/run/stream', route => route.fulfill({ contentType: 'text/event-stream',
    body: 'event: NODE_STARTED\ndata: {"nodeId":"report","runId":"INTERIM-ONLY"}\n\n' +
      'event: WORKFLOW_COMPLETED\ndata: {"data":{"status":"SUCCESS","finalOutput":"STREAM_REPORT"}}\n\n' }))
  await page.getByRole('button', { name: '实时执行', exact: true }).click()
  await page.getByText('STREAM_REPORT', { exact: true }).waitFor()
  await page.getByText("\u8fd0\u884c\u7f16\u53f7\u7f3a\u5931\uff0c\u672c\u6b21\u62a5\u544a\u4e0d\u80fd\u767b\u8bb0", { exact: false }).waitFor()
  assert.equal(await page.getByRole('button', { name: "\u786e\u8ba4\u767b\u8bb0\u4e3a\u62a5\u544a\u65b0\u7248\u672c", exact: true }).isDisabled(), true)
  await page.locator('.MuiChip-label').filter({ hasText: /^分析完成，缺少运行编号$/ }).waitFor()
  assert.equal(await page.locator('.MuiChip-label').filter({ hasText: /^状态未确认$/ }).count(), 1)
  assert.equal(await page.locator('.MuiChip-label').filter({ hasText: /^(idle|running)$/ }).count(), 0)
  assert.equal(await page.getByText('流程完成', { exact: true }).count(), 1)
  assert.equal(await page.getByText('节点开始', { exact: true }).count(), 1)
  assert.equal(await page.locator('[title="WORKFLOW_COMPLETED"]').count(), 1)
  assert.deepEqual(errors, [])
  console.log('PASS terminal workflow status overrides stale running node state')
  await page.unroute('**/api/workflows/run/stream')
  await page.route('**/api/workflows/run/stream', route => route.fulfill({ contentType: 'text/event-stream',
    body: 'event: NODE_STARTED\ndata: {"nodeId":{"unexpected":"value"},"nodeType":{"unexpected":"type"},"data":{"costMs":{"unexpected":"duration"}}}\n\n' +
      'event: WORKFLOW_COMPLETED\ndata: {"data":{"status":"SUCCESS","finalOutput":"MALFORMED_EVENT_REPORT"}}\n\n' }))
  await page.getByRole('button', { name: '实时执行', exact: true }).click()
  await page.getByText('MALFORMED_EVENT_REPORT', { exact: true }).waitFor()
  assert.equal(await page.getByRole('button', { name: '确认登记为报告新版本', exact: true }).count(), 1)
  assert.deepEqual(errors, [])
  console.log('PASS malformed node metadata cannot crash the event list or hide a valid final result')
  await page.route('**/api/workflows/run', route => route.fulfill({ json: { success: true, data: {
    status: 'SUCCESS', finalOutput: 'STRUCTURED_REPORT',
    citations: [null, { title: { unexpected: true }, excerpt: 'BAD_CITATION' }, { title: 'GOOD_CITATION', source: 'fixture-source', excerpt: 'fixture-quote' }, { title: 'NO_QUOTE', source: 'fixture-source', excerpt: { unexpected: true } }],
    risks: [{ title: 'GOOD_RISK', description: { unexpected: true } }]
  } } }))
  await page.getByRole('button', { name: '同步执行', exact: true }).click()
  await page.getByText('STRUCTURED_REPORT', { exact: true }).waitFor()
  assert.equal(await page.getByText('GOOD_CITATION', { exact: true }).count(), 1)
  assert.equal(await page.getByText('BAD_CITATION', { exact: true }).count(), 0)
  assert.equal(await page.getByText('NO_QUOTE', { exact: true }).count(), 0)
  assert.equal(await page.getByText('GOOD_RISK', { exact: true }).count(), 1)
  assert.deepEqual(errors, [])
  console.log('PASS malformed structured citation and risk fields cannot crash the report view')
  await page.locator('input[type=file]').setInputFiles({ name: 'simulated.txt', mimeType: 'text/plain', buffer: Buffer.from('Mock attachment, not legal evidence.') })
  await page.getByText('已选材料仅随“实时执行”提交；同步执行不接收附件。', { exact: true }).waitFor()
  assert.equal(await page.getByRole('button', { name: '同步执行', exact: true }).isDisabled(), true)
  await page.getByRole('button', { name: '移除材料', exact: true }).click()
  assert.equal(await page.getByRole('button', { name: '同步执行', exact: true }).isEnabled(), true)
  await page.locator('input[type=file]').setInputFiles({ name: 'simulated.txt', mimeType: 'text/plain', buffer: Buffer.from('Mock attachment, not legal evidence.') })
  await page.getByRole('button', { name: '移除材料', exact: true }).waitFor()
  assert.equal(await page.getByRole('button', { name: '同步执行', exact: true }).isDisabled(), true)
  assert.deepEqual(errors, [])
  console.log('PASS selected attachment disables text-only sync; removing and reselecting the same file works')
  await page.getByRole('button', { name: '移除材料', exact: true }).click()
  for (const width of [390, 1280]) {
    await page.setViewportSize({ width, height: 844 })
    const reportHeading = await page.getByRole('heading', { name: '候选分析报告', exact: true }).boundingBox()
    const nodesHeading = await page.getByRole('heading', { name: '流程节点', exact: true }).boundingBox()
    assert.equal(width < 1200 ? reportHeading.y < nodesHeading.y : reportHeading.x > nodesHeading.x, true)
    const nodeList = page.getByRole('region', { name: '流程节点列表', exact: true })
    const dimensions = await nodeList.evaluate(element => ({ height: element.clientHeight, total: element.scrollHeight }))
    assert.equal(dimensions.height <= (width < 1200 ? 280 : 480), true)
    assert.equal(dimensions.total > dimensions.height, true)
    await nodeList.focus()
    await nodeList.press('End')
    await page.waitForFunction(() => document.querySelector('[aria-label="流程节点列表"]').scrollTop > 0)
    assert.equal(await nodeList.getByText('报告', { exact: true }).count(), 1)
    await nodeList.evaluate(element => { element.scrollTop = 0 })
    console.log('PASS compact, keyboard-scrollable node list and report placement at width ' + width)
    await page.locator('input[type=file]').setInputFiles({
      name: 'case-material-with-a-very-long-filename-for-responsive-testing-2026-11-13.txt',
      mimeType: 'text/plain', buffer: Buffer.from('Mock attachment only.')
    })
    await page.getByRole('button', { name: '移除材料', exact: true }).waitFor()
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth), true,
      JSON.stringify(await page.evaluate(() => ({ width: innerWidth, scrollWidth: document.documentElement.scrollWidth,
        regions: [...document.querySelectorAll('[role=region]')].map(element => ({ width: element.clientWidth, scrollWidth: element.scrollWidth, rect: element.getBoundingClientRect().right, parentWidth: element.parentElement.clientWidth, ancestors: [element, element.parentElement, element.parentElement.parentElement, element.parentElement.parentElement.parentElement].map(el => [el.tagName, String(el.className).slice(0, 40), el.clientWidth, el.getBoundingClientRect().right]) })), overflowing: [...document.querySelectorAll('main *')].filter(element => element.getBoundingClientRect().right > innerWidth + 2).slice(0, 8).map(element => ({ tag: element.tagName, className: String(element.className).slice(0, 90), right: element.getBoundingClientRect().right, parent: String(element.parentElement?.className).slice(0, 70), overflow: getComputedStyle(element).overflowX })) }))))
    await page.getByRole('button', { name: '移除材料', exact: true }).click()
    assert.equal(await page.getByRole('button', { name: '同步执行', exact: true }).isEnabled(), true)
    console.log('PASS long attachment name fits viewport and remove button works at width ' + width)
  }
  assert.deepEqual(errors, [])
  let imageRequests = 0
  await page.route('https://example.invalid/**', route => {
    imageRequests++
    return route.abort()
  })
  await page.route('**/api/workflows/run', route => route.fulfill({ json: { success: true, data: {
    status: 'SUCCESS', finalOutput: 'SAFE_REPORT\n\n![TRACKER_TEST](https://example.invalid/report.png?case=mock)'
  } } }))
  await page.getByRole('button', { name: '同步执行', exact: true }).click()
  await page.getByText('SAFE_REPORT', { exact: true }).waitFor()
  await page.getByText('[外部图片未加载：TRACKER_TEST]', { exact: true }).waitFor()
  assert.equal(await page.locator('main img').count(), 0)
  assert.equal(imageRequests, 0)
  assert.deepEqual(errors, [])
  console.log('PASS model Markdown images are replaced with text without external requests')
  await page.route('**/api/workflows/run', route => route.fulfill({ json: { success: true, data: {
    status: 'SUCCESS', finalOutput: 'LINK_REPORT\n\n[安全链接](https://example.com/source) [危险链接](javascript:alert(1))'
  } } }))
  await page.getByRole('button', { name: '同步执行', exact: true }).click()
  await page.getByText('安全链接', { exact: true }).waitFor()
  const safeLink = page.getByRole('link', { name: '安全链接', exact: true })
  assert.equal(await safeLink.getAttribute('href'), 'https://example.com/source')
  assert.equal(await safeLink.getAttribute('rel'), 'noreferrer noopener')
  assert.equal(await page.getByRole('link', { name: '危险链接', exact: true }).count(), 0)
  assert.equal(await page.getByText('危险链接', { exact: true }).count(), 1)
  console.log('PASS report links allow only HTTP(S) and add safe external-link attributes')
  const longToken = 'UNBROKEN_REPORT_TEXT_' + 'x'.repeat(320)
  const codeLine = 'CODE_LINE_' + 'y'.repeat(320)
  const tableCells = Array.from({ length: 8 }, (_, index) => 'COLUMN_' + index)
  const wideReport = ['LAYOUT_REPORT', longToken,
    '[LONG_LINK_' + 'z'.repeat(200) + '](https://example.com/source)',
    ['| ' + tableCells.join(' | ') + ' |', '| ' + tableCells.map(() => '---').join(' | ') + ' |',
      '| ' + tableCells.map(() => '模拟表格内容').join(' | ') + ' |'].join('\n'),
    '\x60\x60\x60text\n' + codeLine + '\n\x60\x60\x60'].join('\n\n')
  await page.unroute('**/api/workflows/run')
  await page.route('**/api/workflows/run', route => route.fulfill({ json: { success: true,
    data: { status: 'SUCCESS', finalOutput: wideReport } } }))
  await page.getByRole('button', { name: '同步执行', exact: true }).click()
  await page.getByText('LAYOUT_REPORT', { exact: true }).waitFor()
  for (const width of [390, 1280]) {
    await page.setViewportSize({ width, height: 844 })
    await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))))
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth), true,
      JSON.stringify(await page.evaluate(() => ({ width: innerWidth, scrollWidth: document.documentElement.scrollWidth,
        regions: [...document.querySelectorAll('[role=region]')].map(element => ({ width: element.clientWidth, scrollWidth: element.scrollWidth, rect: element.getBoundingClientRect().right, parentWidth: element.parentElement.clientWidth, ancestors: [element, element.parentElement, element.parentElement.parentElement, element.parentElement.parentElement.parentElement].map(el => [el.tagName, String(el.className).slice(0, 40), el.clientWidth, el.getBoundingClientRect().right]) })), overflowing: [...document.querySelectorAll('main *')].filter(element => element.getBoundingClientRect().right > innerWidth + 2).slice(0, 8).map(element => ({ tag: element.tagName, className: String(element.className).slice(0, 90), right: element.getBoundingClientRect().right, parent: String(element.parentElement?.className).slice(0, 70), overflow: getComputedStyle(element).overflowX })) }))))
    assert.equal(await page.getByText(longToken, { exact: true }).textContent(), longToken)
    assert.equal((await page.locator('main pre code').textContent()).trimEnd(), codeLine)
    const tableRegion = page.getByRole('region', { name: '报告表格', exact: true })
    const tableSize = await tableRegion.evaluate(element => ({ width: element.clientWidth, total: element.scrollWidth }))
    assert.equal(tableSize.total > tableSize.width, true)
    await tableRegion.focus()
    await tableRegion.press('ArrowRight')
    await page.waitForFunction(() => document.querySelector('[aria-label="报告表格"]').scrollLeft > 0)
    await tableRegion.evaluate(element => { element.scrollLeft = 0 })
    assert.deepEqual(errors, [])
    console.log('PASS long report text, links, code and keyboard-scrollable tables fit viewport ' + width)
  }
  await page.route('**/api/governance/readiness', route => route.fulfill({ json: { success: true, data: { capabilities: { model_runtime: { status: 'CONFIGURED_UNVERIFIED' } } } } }))
  await page.route('**/api/workflows/uploads/authorize', route => route.fulfill({ json: { success: true, data: { token: 'MOCK-CONTRACT-TOKEN' } } }))
  for (const status of ['SUCCESS', 'REVIEW_REQUIRED', 'BLOCKED', 'FAILED', 'LATE_FAILURE']) {
    const data = {
      status: status === 'LATE_FAILURE' ? 'SUCCESS' : status, finalOutput: 'CONTRACT_REPORT_MARKER',
      riskItems: [null, { title: { invalid: true } }, { title: 'CONTRACT_RISK_MARKER', description: { invalid: true } }],
      citationItems: [null, { title: 'NO_SOURCE' }, { title: 'MOCK_SOURCE', source: 'fixture-source', excerpt: 'fixture-quote' }]
    }
    await page.route('**/api/workflows/run/stream/upload', route => route.fulfill({ contentType: 'text/event-stream',
      body: 'event: WORKFLOW_COMPLETED\ndata: ' + JSON.stringify({ runId: 'MOCK-CONTRACT-RUN', data }) + '\n\n' +
        (status === 'LATE_FAILURE' ? 'event: WORKFLOW_FAILED\ndata: {"message":"MOCK_LATE_FAILURE"}\n\n' : '') }))
    await page.goto(origin + '/contract-review')
    await page.locator('input[type=file]').setInputFiles({ name: 'mock-contract.pdf', mimeType: 'application/pdf', buffer: Buffer.from('Mock file; parser is not exercised.') })
    await page.getByRole('button', { name: '开始审查', exact: true }).click()
    if (['SUCCESS', 'REVIEW_REQUIRED'].includes(status)) {
      await page.getByText('CONTRACT_REPORT_MARKER', { exact: true }).waitFor()
      await page.getByText('CONTRACT_RISK_MARKER', { exact: true }).waitFor()
      await page.getByText('来源与摘录齐全的候选引用： 1 条；法律适用仍需人工核验。', { exact: true }).waitFor()
      assert.equal(await page.getByRole('button', { name: '打印当前报告', exact: true }).isEnabled(), true)
    } else {
      await page.getByText('本次审查未完成，未展示风险结论或引用。请检查错误后重新提交。', { exact: true }).waitFor()
      assert.equal((await page.locator('main').innerText()).includes('CONTRACT_REPORT_MARKER'), false)
      assert.equal((await page.locator('main').innerText()).includes('CONTRACT_RISK_MARKER'), false)
      assert.equal(await page.getByRole('button', { name: '打印当前报告', exact: true }).isDisabled(), true)
      assert.equal(await page.getByRole('button', { name: '确认登记为报告新版本', exact: true }).count(), 0)
    }
    assert.deepEqual(errors, [])
    await page.unroute('**/api/workflows/run/stream/upload')
    console.log('PASS mocked contract ' + status + ' protects report visibility and malformed structured fields')
  }
  await page.goto(origin + '/risk-inspection')
  assert.equal(await page.getByRole('button', { name: '巡检未开放', exact: true }).isDisabled(), true)
  assert.equal(await page.getByRole('button', { name: '运行巡检', exact: true }).count(), 0)
  assert.deepEqual(errors, [])
  console.log('PASS unavailable risk inspection has no simulated run action')
  for (const width of [390, 1280]) {
    await page.setViewportSize({ width, height: 900 })
    let matterResponse = { success: true, data: [{ matter: { id: 'MOCK-MATTER', title: 'MOCK_OLD_MATTER', clientReference: 'MOCK-REF', status: 'INTAKE', version: 1 }, scope: { businessType: 'CONTRACT' } }] }
    await page.route('**/api/legal/matters', route => route.fulfill({ json: matterResponse }))
    await page.goto(origin + '/matters')
    await page.getByText('MOCK_OLD_MATTER', { exact: true }).waitFor()
    for (const invalidResponse of [{ success: false, message: '模拟读取失败' }, { success: true, data: {} }]) {
      matterResponse = invalidResponse
      await page.getByRole('button', { name: '刷新', exact: true }).click()
      await page.getByText('加载失败，请重试', { exact: true }).waitFor()
      assert.equal(await page.getByText('MOCK_OLD_MATTER', { exact: true }).count(), 0)
      assert.equal(await page.getByText('MOCK_NEW_MATTER', { exact: true }).count(), 0)
      assert.equal(await page.getByText('事项列表已清空，请重试获取最新状态。', { exact: false }).count(), 1)
      assert.equal(await page.getByRole('heading', { name: '—', exact: true }).count(), 3)
      assert.equal(await page.getByRole('tab', { name: '全部 · —', exact: true }).count(), 1)
      assert.deepEqual(errors, [])
      matterResponse = { success: true, data: [{ matter: { id: 'MOCK-NEW', title: 'MOCK_NEW_MATTER', clientReference: 'MOCK-NEW', status: 'INTAKE', version: 1 }, scope: { businessType: 'CONTRACT' } }] }
      await page.getByRole('button', { name: '重试', exact: true }).click()
      await page.getByText('MOCK_NEW_MATTER', { exact: true }).waitFor()
    }
    matterResponse = { success: true, data: [] }
    await page.getByRole('button', { name: '刷新', exact: true }).click()
    await page.getByText('暂无符合条件的事项', { exact: true }).waitFor()
    assert.equal(await page.getByRole('heading', { name: '0', exact: true }).count(), 3)
    assert.equal(await page.getByRole('tab', { name: '全部 · 0', exact: true }).count(), 1)
    assert.equal(await page.getByRole('alert').count(), 0)
    assert.deepEqual(errors, [])
    await page.unroute('**/api/legal/matters')
    console.log('PASS matter refresh failure, recovery and true empty state remain distinct at width ' + width)
  }
  for (const data of [{ unexpected: true }, [null], [['not-an-event']]]) {
    await page.route('**/api/governance/events', route => route.fulfill({ json: { success: true, data } }))
    await page.goto(origin + '/audit')
    await page.getByText('诊断记录格式异常。当前没有展示示例记录。', { exact: true }).waitFor()
    assert.equal(await page.getByText('当前服务未返回模型调用诊断记录。', { exact: true }).count(), 0)
    assert.equal(await page.getByRole('button', { name: '打印当前诊断视图', exact: true }).isDisabled(), true)
    assert.deepEqual(errors, [])
    await page.unroute('**/api/governance/events')
  }
  await page.route('**/api/governance/events', route => route.fulfill({ json: { success: true, data: [
    { detail: 'MOCK_UNKNOWN_EVENT', success: 'false', costMs: -1, clientId: {}, timestamp: {} },
    { detail: 'MOCK_SUCCESS_EVENT', success: true, costMs: 0 },
    { detail: 'MOCK_FAILURE_EVENT', success: false, costMs: 17 }
  ] } }))
  await page.goto(origin + '/audit')
  await page.getByText('MOCK_UNKNOWN_EVENT', { exact: true }).waitFor()
  const unknownEvent = page.getByRole('row').filter({ hasText: 'MOCK_UNKNOWN_EVENT' })
  assert.match(await unknownEvent.innerText(), /结果未知/)
  assert.equal(await unknownEvent.getByText('失败', { exact: true }).count(), 0)
  assert.equal(await unknownEvent.getByText('0 ms', { exact: true }).count(), 0)
  assert.equal((await unknownEvent.innerText()).includes('[object Object]'), false)
  assert.match(await page.getByRole('row').filter({ hasText: 'MOCK_SUCCESS_EVENT' }).innerText(), /成功.*0 ms/s)
  assert.match(await page.getByRole('row').filter({ hasText: 'MOCK_FAILURE_EVENT' }).innerText(), /失败.*17 ms/s)
  assert.deepEqual(errors, [])
  await page.unroute('**/api/governance/events')
  console.log('PASS audit diagnostics reject malformed lists and keep unknown outcomes and durations distinct')
  let uploadCapabilities = { enabled: true, reason: '模拟可用', maxFileBytes: 1048576, allowedExtensions: ['txt'] }
  await page.route('**/api/legal/documents/upload-capabilities', route => route.fulfill({ json: { success: true, data: uploadCapabilities } }))
  let knowledgeStats = { totalDocuments: { unexpected: true }, successDocuments: 0, failedDocuments: 0, totalChunks: 0 }
  await page.route('**/api/legal/documents/stats', route => route.fulfill({ json: { success: true, data: knowledgeStats } }))
  let knowledgeUploadResult = { status: 'STORED', documentId: 'MOCK-STORED', scope: { corpusType: 'FIRM_KNOWLEDGE', tenantId: 'MOCK-TENANT' } }
  await page.route('**/api/legal/documents/upload', route => route.fulfill({ json: { success: true, data: knowledgeUploadResult } }))
  await page.goto(origin + '/knowledge-base')
  await page.getByText(/知识库统计结果格式异常/).waitFor()
  assert.equal(await page.getByText('—', { exact: true }).count(), 4)
  assert.equal(await page.getByText('0', { exact: true }).count(), 0)
  assert.deepEqual(errors, [])
  knowledgeStats = { totalDocuments: 0, successDocuments: 0, failedDocuments: 0, totalChunks: 0 }
  await page.getByRole('button', { name: '刷新状态', exact: true }).click()
  await page.getByText('0', { exact: true }).first().waitFor()
  assert.equal(await page.getByText('0', { exact: true }).count(), 4)
  assert.equal(await page.getByText(/知识库统计结果格式异常/).count(), 0)
  console.log('PASS malformed knowledge statistics remain unknown and recover to genuine zero counts')
  for (const malformed of [
    { ...uploadCapabilities, reason: { bad: true } },
    { ...uploadCapabilities, allowedExtensions: [{}] },
    { ...uploadCapabilities, maxFileBytes: 0 }
  ]) {
    uploadCapabilities = malformed
    await page.getByRole('button', { name: '刷新状态', exact: true }).click()
    await page.getByText(/上传能力信息异常，请重试/).waitFor()
    assert.equal(await page.getByRole('button', { name: '选择文档', exact: true }).isDisabled(), true)
    assert.deepEqual(errors, [])
  }
  uploadCapabilities = { enabled: true, reason: '模拟可用', maxFileBytes: 1048576, allowedExtensions: ['txt'] }
  await page.getByRole('button', { name: '刷新状态', exact: true }).click()
  await page.getByText(/模拟可用/).waitFor()
  assert.equal(await page.getByRole('button', { name: '选择文档', exact: true }).isEnabled(), true)
  console.log('PASS malformed upload capabilities fail closed and valid settings restore file selection')
  await page.locator('input[type=file]').setInputFiles({ name: 'MOCK_KNOWLEDGE.txt', mimeType: 'text/plain', buffer: Buffer.from('fixture') })
  for (const incomplete of [
    { ...knowledgeUploadResult },
    { ...knowledgeUploadResult, objectUri: 'file:/mock', versionHash: 'a'.repeat(64), scope: { corpusType: 'MATTER_PRIVATE', tenantId: 'MOCK-TENANT' } }
  ]) {
    knowledgeUploadResult = incomplete
    await page.getByRole('button', { name: '确认上传', exact: true }).click()
    await page.getByText(/服务器未返回完整的律所知识存储结果/).waitFor()
    assert.equal(await page.getByText('已存储，待入库', { exact: true }).count(), 0)
    await page.getByText(/已选择：MOCK_KNOWLEDGE.txt/).waitFor()
    assert.deepEqual(errors, [])
  }
  knowledgeUploadResult = { status: 'STORED', documentId: 'MOCK-STORED', objectUri: 'file:/mock', versionHash: 'a'.repeat(64), scope: { corpusType: 'FIRM_KNOWLEDGE', tenantId: 'MOCK-TENANT' } }
  await page.getByRole('button', { name: '确认上传', exact: true }).click()
  await page.getByText('已存储，待入库', { exact: true }).waitFor()
  await page.getByRole('button', { name: '刷新状态', exact: true }).click()
  await page.getByText('已存储，待入库', { exact: true }).waitFor()
  assert.equal(await page.getByText('离开页面后此列表不保留；刷新状态不会清除本页记录。', { exact: false }).count(), 1)
  assert.deepEqual(errors, [])
  await page.unroute('**/api/legal/documents/stats')
  await page.unroute('**/api/legal/documents/upload')
  await page.unroute('**/api/legal/documents/upload-capabilities')
  console.log('PASS knowledge upload rejects incomplete or mismatched receipts, retains draft and accepts complete receipt')
  await page.goto(origin + '/law-library')
  await page.getByText('暂不能搜索法规或判例；页面不会展示模拟法条，也不提供无法执行的搜索操作。', { exact: true }).waitFor()
  assert.equal(await page.locator('main input').count(), 0)
  assert.equal(await page.getByRole('button', { name: '订阅新法提醒', exact: true }).count(), 0)
  assert.deepEqual(errors, [])
  console.log('PASS unconnected public law library exposes no fake search or subscription actions')
  await page.goto(origin + '/agent-studio')
  await page.getByText('待落实的服务规则', { exact: true }).waitFor()
  assert.equal(await page.locator('main input[type=checkbox]').count(), 0)
  assert.equal(await page.getByRole('switch').count(), 0)
  assert.equal(await page.getByRole('button', { name: /保存草稿/ }).count(), 0)
  assert.equal(await page.getByRole('button', { name: '运行服务测试', exact: true }).count(), 1)
  assert.deepEqual(errors, [])
  console.log('PASS draft agent design has no inert policy toggles or fake save action')
  await page.route('**/api/agent/tool-call', route => route.fulfill({ json: { success: true, data: { content: { untrusted: true } } } }))
  await page.getByRole('button', { name: '运行服务测试', exact: true }).click()
  await page.getByText('服务端未返回可显示文本', { exact: true }).waitFor()
  assert.equal((await page.locator('main').innerText()).includes('[object Object]'), false)
  await page.unroute('**/api/agent/tool-call')
  await page.route('**/api/agent/tool-call', route => route.fulfill({ json: { success: true, data: { content: 'MOCK_AGENT_REPLY' } } }))
  await page.getByRole('button', { name: '运行服务测试', exact: true }).click()
  await page.getByText('MOCK_AGENT_REPLY', { exact: true }).waitFor()
  assert.equal(await page.getByText('服务端未返回可显示文本', { exact: true }).count(), 0)
  assert.deepEqual(errors, [])
  await page.unroute('**/api/agent/tool-call')
  console.log('PASS agent test rejects malformed service content and recovers on a valid reply')
  console.log('Mocked frontend regression only; not backend acceptance or legal accuracy evidence.')
} finally {
  if (browser) await browser.close()
  await server.close()
}

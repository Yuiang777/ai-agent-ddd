import test from 'node:test'
import assert from 'node:assert/strict'
import { canAccessWorkspacePath, isClientAudience, navigationForRole } from '../src/navigation.js'

test('client navigation contains only customer-facing services', () => {
  const paths = navigationForRole('CLIENT').flatMap(group => group.items.map(item => item.path))
  assert.deepEqual(paths, ['/dashboard', '/matters', '/chat', '/law-library'])
  assert.ok(!paths.includes('/audit'))
  assert.ok(!paths.includes('/agent-studio'))
  assert.ok(!paths.includes('/settings'))
})

test('customer audience is fail-closed when role is missing or unknown', () => {
  for (const role of [undefined, null, 'UNKNOWN', 'CLIENT']) assert.equal(isClientAudience(role), true)
  for (const role of ['LAWYER', 'PARALEGAL', 'SYSTEM']) assert.equal(isClientAudience(role), false)
})

test('client route guard blocks staff work areas while preserving customer pages', () => {
  for (const path of ['/dashboard', '/chat', '/matters', '/law-library']) assert.equal(canAccessWorkspacePath('CLIENT', path), true)
  for (const path of ['/settings', '/audit', '/agent-studio', '/knowledge-base', '/workflow']) assert.equal(canAccessWorkspacePath('CLIENT', path), false)
  assert.equal(canAccessWorkspacePath('LAWYER', '/settings'), true)
})

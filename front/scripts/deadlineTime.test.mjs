import assert from 'node:assert/strict'
import test from 'node:test'
import { normalizeDeadlineTime, formatDeadlineInZone } from '../src/api/deadlineTime.js'
test('Shanghai explicit offset is converted to UTC and rendered in the matter zone', () => {
  const value = normalizeDeadlineTime('2026-12-01T17:00:00+08:00', 'Asia/Shanghai')
  assert.equal(value, '2026-12-01T09:00:00.000Z')
  assert.match(formatDeadlineInZone(value, 'Asia/Shanghai'), /17:00:00/)
})
test('nonexistent calendar dates, unknown offset and invalid zone are rejected', () => {
  for (const value of ['2026-02-29T17:00+08:00', '2026-04-31T17:00+08:00', '2026-13-01T17:00+08:00', '2026-12-01T24:00+08:00', '2026-12-01T17:00-00:00']) assert.throws(() => normalizeDeadlineTime(value, 'Asia/Shanghai'))
  assert.throws(() => normalizeDeadlineTime('2026-12-01T17:00+08:00', 'Invalid/Zone'))
})
test('leap years and UTC minutes are accepted', () => {
  assert.equal(normalizeDeadlineTime('2028-02-29T17:00+08:00', 'Asia/Shanghai'), '2028-02-29T09:00:00.000Z')
  assert.equal(normalizeDeadlineTime('2026-12-01T17:00Z', 'UTC'), '2026-12-01T17:00:00.000Z')
})
test('summer time gap and an offset inconsistent with the zone are rejected', () => {
  assert.throws(() => normalizeDeadlineTime('2026-03-08T02:30:00-05:00', 'America/New_York'))
  assert.throws(() => normalizeDeadlineTime('2026-07-01T17:00:00-05:00', 'America/New_York'))
  assert.equal(normalizeDeadlineTime('2026-07-01T17:00:00-04:00', 'America/New_York'), '2026-07-01T21:00:00.000Z')
})
test('explicit offsets disambiguate both autumn repeated times', () => {
  assert.equal(normalizeDeadlineTime('2026-11-01T01:30:00-04:00', 'America/New_York'), '2026-11-01T05:30:00.000Z')
  assert.equal(normalizeDeadlineTime('2026-11-01T01:30:00-05:00', 'America/New_York'), '2026-11-01T06:30:00.000Z')
})

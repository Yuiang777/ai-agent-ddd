/** Validate an explicitly offset local time against its legal matter's IANA zone. */
export function normalizeDeadlineTime(value, zoneId) {
  const match = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::(\d{2}))?(Z|([+-])(\d{2}):(\d{2}))$/.exec(value)
  if (!match) throw new Error('截止时间须包含完整日期、时分及 Z 或明确时区偏移')
  const [, year, month, day, hour, minute, second = '00', offset, , offsetHour = '0', offsetMinute = '0'] = match
  const [y, m, d, h, min, sec, oh, om] = [year, month, day, hour, minute, second, offsetHour, offsetMinute].map(Number)
  const leap = y % 4 === 0 && (y % 100 !== 0 || y % 400 === 0)
  const days = [31, leap ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31]
  if (y < 1 || m < 1 || m > 12 || d < 1 || d > days[m - 1] || h > 23 || min > 59 || sec > 59 || oh > 18 || om > 59 || (oh === 18 && om !== 0) || offset === '-00:00') {
    throw new Error('截止时间包含无效日期、时刻或时区偏移，请核对原始通知')
  }
  const instant = new Date(value)
  if (!Number.isFinite(instant.getTime())) throw new Error('截止时间无效')
  let formatter
  try {
    formatter = new Intl.DateTimeFormat('en-GB', { timeZone: zoneId, year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23' })
  } catch { throw new Error('请输入有效的 IANA 办理时区，例如 Asia/Shanghai') }
  const parts = Object.fromEntries(formatter.formatToParts(instant).map(part => [part.type, part.value]))
  if ([['year', y], ['month', m], ['day', d], ['hour', h], ['minute', min], ['second', sec]].some(([key, expected]) => Number(parts[key]) !== expected)) {
    throw new Error('时间偏移与办理时区不一致，或该时刻处于夏令时跳时区间。请按通知核对日期和偏移')
  }
  return instant.toISOString()
}
export function formatDeadlineInZone(value, zoneId) {
  try {
    return new Intl.DateTimeFormat('zh-CN', { timeZone: zoneId, year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23' }).format(new Date(value))
  } catch { return '时间或办理时区无效，请核对记录' }
}

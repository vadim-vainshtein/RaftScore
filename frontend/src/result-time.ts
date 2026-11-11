export function parseTime(value: string): number | null {
  const match = /^(?:(\d+):([0-5]\d)|(\d+))(?:[.,](\d{1,2}))?$/.exec(value.trim())
  if (!match) return null
  const seconds = match[3] === undefined ? Number(match[1]) * 60 + Number(match[2]) : Number(match[3])
  const total = seconds * 100 + Number((match[4] ?? '').padEnd(2, '0'))
  return Number.isSafeInteger(total) && total <= 2147483647 ? total : null
}

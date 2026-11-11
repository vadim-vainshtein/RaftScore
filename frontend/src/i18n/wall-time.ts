/** Convert competition wall time to an instant. During a DST overlap, choose the earlier instant. */
export function wallTimeToInstant(value: string, zone: string): Date | null {
  const match = /^(\d{4})-(\d\d)-(\d\d)T(\d\d):(\d\d)(?::(\d\d))?$/.exec(value)
  if (!match) return null
  const parts = match.slice(1).map(Number)
  const wall = Date.UTC(parts[0], parts[1] - 1, parts[2], parts[3], parts[4], parts[5] || 0)
  const formatter = new Intl.DateTimeFormat('en-GB', {timeZone: zone, year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23'})
  const asWall = (instant: number) => {
    const p = Object.fromEntries(formatter.formatToParts(instant).map(part => [part.type, Number(part.value)]))
    return Date.UTC(p.year, p.month - 1, p.day, p.hour, p.minute, p.second)
  }
  const offsets = new Set<number>()
  for (let hours = -48; hours <= 48; hours += 6) {
    const instant = wall + hours * 3600000
    offsets.add(asWall(instant) - instant)
  }
  const matches = [...offsets].map(offset => wall - offset).filter(instant => asWall(instant) === wall).sort((a, b) => a - b)
  return matches.length ? new Date(matches[0]) : null
}

import {describe, expect, it} from 'vitest'
import {parseTime} from './result-time'

describe('parseTime', () => {
  it('parses exact centiseconds in seconds and minute formats', () => {
    for (const value of ['74.25', '1:14.25', '01:14,25']) expect(parseTime(value)).toBe(7425)
    expect(parseTime('0.1')).toBe(10)
  })
  it('rejects invalid and overflowing values', () => {
    for (const value of ['', '-1', '1:60', '1.234', '21474836.48']) expect(parseTime(value)).toBeNull()
  })
})

import { expect, test, type APIRequestContext, type Page } from '@playwright/test'

type Crew = { id: number, name: string }
type Attempt = { crewId: number, attemptNumber: number, durationCentiseconds: number, gates: { number: number, penaltySeconds: number }[] }

const admin = { username: 'e2e-admin', password: 'e2e-password' }
const gateCount = 25

test.use({ viewport: { width: 768, height: 900 }, locale: 'en-US' })
test.setTimeout(90000)

async function read<T>(api: APIRequestContext, path: string): Promise<T> {
  const response = await api.get(path)
  await expect(response).toBeOK()
  return response.json()
}

async function write(api: APIRequestContext, method: 'post' | 'put', path: string, data: object) {
  const csrf = await read<{ headerName: string, token: string }>(api, '/api/auth/csrf')
  const response = await api[method](path, { data, headers: { [csrf.headerName]: csrf.token } })
  await expect(response).toBeOK()
  return response
}

async function seed(api: APIRequestContext, title: string, crewCount: number, type = 'SPRINT') {
  await write(api, 'post', '/api/auth/login', admin)
  const setup = await write(api, 'post', '/api/competitions/setup', {
    competition: { title, startDate: '2026-04-01', location: 'River', timeZone: 'UTC', admissionConfirmationRequired: false },
    categories: [{ classLabel: 'R1', scoringGroupLabel: 'Open', crewSize: 1 }],
    requirements: [],
    disciplines: [{
      name: type === 'SLALOM' ? 'Slalom' : 'Sprint', type, displayOrder: 0, categoryIndexes: [0],
      ...(type === 'SLALOM' ? { gateCount, attemptCount: 1 } : {}),
      pointsTable: { name: 'Points', tiePolicy: 'SHARED_PLACE_SAME_POINTS', rows: [{ place: 1, points: 100 }] },
    }],
  })
  const { id } = await setup.json()
  const competition = `/api/competitions/${id}`
  const [[category], [discipline]] = await Promise.all([
    read<{ id: number }[]>(api, `${competition}/categories`),
    read<{ id: number }[]>(api, `${competition}/disciplines`),
  ])
  const crews: Crew[] = []
  for (let n = 1; n <= crewCount; n++) {
    const name = `Wave ${n}`
    const entry = await write(api, 'post', `${competition}/entries`, {
      categoryId: category.id, crewName: name, regionOrClub: 'Club', representative: 'Coach',
      startNumber: n, roster: [{ surname: `Racer ${n}`, givenName: 'Alex' }],
    })
    crews.push({ id: (await entry.json()).id, name })
  }
  const base = `${competition}/disciplines/${discipline.id}/categories/${category.id}`
  await write(api, 'put', `${base}/start-list`, { random: false, crewIds: crews.map(crew => crew.id) })
  const route = `/?lang=en#/result-grid?competition=${id}&discipline=${discipline.id}&category=${category.id}`
  return { route, base, crews }
}

function attemptPath(base: string, crew: Crew) {
  return `${base}/crews/${crew.id}/attempts/1`
}

function time(page: Page, crew: Crew) {
  return page.getByLabel(`Time ${crew.name}`, { exact: true })
}

async function openGrid(page: Page, route: string, first: Crew) {
  await page.goto(route)
  const username = page.getByLabel('Username')
  await expect(username.or(time(page, first))).toBeVisible()
  if (await username.isVisible()) {
    await username.fill(admin.username)
    await page.getByLabel('Password').fill(admin.password)
    await page.getByRole('button', { name: 'Sign in' }).click()
  }
  await expect(time(page, first)).toBeEnabled()
}

async function saveWithKeyboard(page: Page, path: string) {
  const response = page.waitForResponse(r => r.url().endsWith(path) && r.request().method() === 'PUT')
  await page.keyboard.press('Control+s')
  return response
}

async function storedAttempts(api: APIRequestContext, base: string) {
  const grid = await read<{ attempts: Attempt[] }>(api, `${base}/attempt-grid`)
  return grid.attempts
}

async function prepareSprint(api: APIRequestContext, title: string) {
  const fixture = await seed(api, title, 1)
  await write(api, 'put', attemptPath(fixture.base, fixture.crews[0]), {
    durationCentiseconds: 7401, penaltyCentiseconds: 0, status: 'FINISHED', gates: [],
  })
  return fixture
}

test('keyboard saves 20 sprint results consecutively', async ({ page }) => {
  const { route, base, crews } = await seed(page.request, 'Keyboard sprint', 20)
  await openGrid(page, route, crews[0])
  await time(page, crews[0]).focus()

  for (const [index, crew] of crews.entries()) {
    const cents = String(index + 1).padStart(2, '0')
    await expect(time(page, crew)).toBeFocused()
    await page.keyboard.type(`74.${cents}`)
    const response = await saveWithKeyboard(page, attemptPath(base, crew))
    expect(response.status()).toBe(200)
    await expect(time(page, crew)).toHaveValue(`01:14.${cents}`)
  }

  const attempts = await storedAttempts(page.request, base)
  expect(attempts).toHaveLength(20)
  for (const [index, crew] of crews.entries()) {
    expect(attempts.find(attempt => attempt.crewId === crew.id)?.durationCentiseconds).toBe(7401 + index)
  }
})

test('keeps the draft when PUT fails and retries only GET after a saved result', async ({ page }) => {
  const { route, base, crews: [crew] } = await prepareSprint(page.request, 'Sprint with interrupted requests')
  await openGrid(page, route, crew)
  const input = time(page, crew)
  const writePath = attemptPath(base, crew)
  const gridPath = `${base}/attempt-grid`
  await input.fill('76.00')
  await page.route(`**${writePath}`, route => route.abort('internetdisconnected'), { times: 1 })
  await page.keyboard.press('Control+s')
  await expect(page.getByText('Save could not be confirmed. Your draft is kept.')).toBeVisible()
  await expect(input).toHaveValue('76.00')
  expect((await storedAttempts(page.request, base))[0].durationCentiseconds).toBe(7401)

  await page.route(`**${gridPath}`, route => route.abort('internetdisconnected'), { times: 1 })
  await input.focus()
  expect((await saveWithKeyboard(page, writePath)).status()).toBe(200)
  await expect(page.getByText(`Result saved for ${crew.name} (attempt 1).`, { exact: true })).toBeVisible()
  await expect(page.getByText('Calculated data may be out of date.')).toBeVisible()
  await expect(page.getByText('Save could not be confirmed. Your draft is kept.')).toBeHidden()
  await expect(input).toHaveValue('01:16.00')
  expect((await storedAttempts(page.request, base))[0].durationCentiseconds).toBe(7600)

  const requests: string[] = []
  page.on('request', request => {
    if (request.url().endsWith(gridPath) || request.url().endsWith(writePath)) requests.push(request.method())
  })
  const refreshed = page.waitForResponse(r => r.url().endsWith(gridPath) && r.request().method() === 'GET')
  await page.getByRole('button', { name: 'Retry refresh' }).click()
  expect((await refreshed).status()).toBe(200)
  await expect(page.getByText('Calculated data may be out of date.')).toBeHidden()
  await expect(input).toHaveValue('01:16.00')
  expect(requests).toEqual(['GET'])
})

test('preserves an earlier refresh failure when the next PUT also fails', async ({ page }) => {
  const { route, base, crews: [first, second] } = await seed(page.request, 'Overlapping sprint requests', 2)
  await openGrid(page, route, first)
  const gridPath = `${base}/attempt-grid`
  const heldRefresh = Promise.withResolvers<void>()
  const releaseRefresh = Promise.withResolvers<void>()
  await page.route(`**${attemptPath(base, second)}`, route => route.abort('internetdisconnected'), { times: 1 })
  await page.route(`**${gridPath}`, async route => {
    heldRefresh.resolve()
    await releaseRefresh.promise
    await route.abort('internetdisconnected')
  })

  try {
    await time(page, first).fill('76.00')
    expect((await saveWithKeyboard(page, attemptPath(base, first))).status()).toBe(200)
    await heldRefresh.promise
    const next = time(page, second)
    await expect(next).toBeFocused()
    await page.keyboard.type('77.00')
    await page.keyboard.press('Control+s')
    await expect(page.getByText('Save could not be confirmed. Your draft is kept.')).toBeVisible()
    await expect(next).toHaveValue('77.00')
  } finally {
    releaseRefresh.resolve()
  }

  await expect(page.getByRole('button', { name: 'Retry refresh' })).toBeVisible()
  await expect(page.getByText(`Result saved for ${first.name} (attempt 1).`, { exact: true })).toBeVisible()
  await expect(page.getByText(`Result saved for ${second.name} (attempt 1).`, { exact: true })).toBeHidden()
  expect(await storedAttempts(page.request, base)).toMatchObject([{ crewId: first.id, durationCentiseconds: 7600 }])
})

test('requires an explicit conflict choice before a stale draft can be saved', async ({ page, browser, baseURL }) => {
  const { route, base, crews: [crew] } = await prepareSprint(page.request, 'Two judges sprint')
  const context = await browser.newContext({ baseURL, viewport: { width: 768, height: 900 }, locale: 'en-US' })
  try {
    const second = await context.newPage()
    await Promise.all([openGrid(page, route, crew), openGrid(second, route, crew)])
    const [firstTime, secondTime] = [time(page, crew), time(second, crew)]
    await expect(firstTime).toHaveValue('01:14.01')
    await expect(secondTime).toHaveValue('01:14.01')
    await firstTime.fill('70.11')
    await secondTime.fill('71.22')
    const path = attemptPath(base, crew)
    expect((await saveWithKeyboard(page, path)).status()).toBe(200)
    await expect(firstTime).toHaveValue('01:10.11')
    expect((await saveWithKeyboard(second, path)).status()).toBe(409)
    await expect(secondTime).toHaveValue('71.22')
    expect((await storedAttempts(page.request, base))[0].durationCentiseconds).toBe(7011)

    const latest = second.waitForResponse(r => r.url().endsWith(`${base}/attempt-grid`) && r.request().method() === 'GET')
    await second.getByRole('button', { name: 'Load current version' }).click()
    expect((await latest).status()).toBe(200)
    await expect(second.getByText(/01:10\.11/).first()).toBeVisible()
    await expect(secondTime).toHaveValue('71.22')
    await expect(second.getByRole('button', { name: 'Discard draft' })).toBeVisible()

    const writes: string[] = []
    second.on('request', request => {
      if (request.url().endsWith(path) && request.method() === 'PUT') writes.push(request.method())
    })
    await secondTime.focus()
    await second.keyboard.press('Control+s')
    expect((await storedAttempts(page.request, base))[0].durationCentiseconds).toBe(7011)
    await second.getByRole('button', { name: 'Keep draft' }).click()
    await expect(secondTime).toHaveValue('71.22')
    expect(writes).toEqual([])
    expect((await storedAttempts(page.request, base))[0].durationCentiseconds).toBe(7011)

    await secondTime.focus()
    expect((await saveWithKeyboard(second, path)).status()).toBe(200)
    await expect(secondTime).toHaveValue('01:11.22')
    expect((await storedAttempts(page.request, base))[0].durationCentiseconds).toBe(7122)

    await firstTime.fill('72.33')
    expect((await saveWithKeyboard(page, path)).status()).toBe(409)
    await page.getByRole('button', { name: 'Load current version' }).click()
    await expect(firstTime).toHaveValue('72.33')
    const discardedWrites: string[] = []
    page.on('request', request => {
      if (request.url().endsWith(path) && request.method() === 'PUT') discardedWrites.push(request.method())
    })
    await page.getByRole('button', { name: 'Discard draft' }).click()
    await expect(firstTime).toHaveValue('01:11.22')
    expect((await storedAttempts(page.request, base))[0].durationCentiseconds).toBe(7122)
    expect(discardedWrites).toEqual([])
  } finally {
    await context.close()
  }
})

test('opens and focuses the missing slalom gate on the second page', async ({ page }) => {
  const { route, base, crews: [crew] } = await seed(page.request, 'Slalom with 25 gates', 1, 'SLALOM')
  await openGrid(page, route, crew)
  const input = time(page, crew)
  const gate = (number: number) => page.getByLabel(`Gate ${number} ${crew.name}`, { exact: true })
  const gatePage = page.getByLabel('Gate page (20 per page)', { exact: true })
  await input.fill('90.00')
  for (let n = 1; n <= 20; n++) await gate(n).selectOption(n === 2 ? '5' : '0')
  await gatePage.fill('2')
  for (const n of [21, 22, 24, 25]) await gate(n).selectOption('0')
  await gatePage.fill('1')
  await expect(gate(23)).toHaveCount(0)

  await input.focus()
  await page.keyboard.press('Control+s')
  await expect(page.getByText('Complete gate 23.', { exact: true })).toBeVisible()
  await expect(gatePage).toHaveValue('2')
  await expect(gate(23)).toBeFocused()
  await expect(gate(23)).toHaveAttribute('aria-invalid', 'true')
  await expect.poll(() => gate(23).evaluate(element => {
    const { left, top, width, height } = element.getBoundingClientRect()
    return document.elementFromPoint(left + width / 2, top + height / 2) === element
  })).toBe(true)
  expect(await storedAttempts(page.request, base)).toEqual([])

  await gate(23).selectOption('50')
  await gate(23).focus()
  expect((await saveWithKeyboard(page, attemptPath(base, crew))).status()).toBe(200)
  await expect(input).toHaveValue('01:30.00')
  await expect(gate(23)).toHaveValue('50')
  await expect(page.getByText('02:25.00', { exact: true }).first()).toBeVisible()
  const [saved] = await storedAttempts(page.request, base)
  expect(saved.durationCentiseconds).toBe(9000)
  expect(saved.gates.toSorted((a, b) => a.number - b.number)).toEqual(Array.from({ length: gateCount }, (_, i) => ({
    number: i + 1, penaltySeconds: i === 1 ? 5 : i === 22 ? 50 : 0,
  })))
})

import {expect, request, test, type APIRequestContext, type Page} from '@playwright/test'

test.use({viewport: {width: 360, height: 800}, locale: 'ru-RU'})
test.setTimeout(90000)

async function write(api: APIRequestContext, method: 'post' | 'put', path: string, data: object) {
  const csrf = await (await api.get('/api/auth/csrf')).json()
  const response = await api[method](path, {data, headers: {[csrf.headerName]: csrf.token}})
  expect(response.ok(), `${method.toUpperCase()} ${path}: ${response.status()} ${await response.text()}`).toBeTruthy()
  return response
}

async function seed(baseURL: string) {
  const api = await request.newContext({baseURL})
  try {
    await write(api, 'post', '/api/auth/login', {username: 'e2e-admin', password: 'e2e-password'})
    await write(api, 'post', '/api/users', {username: 'e2e-reader', password: 'reader-password'})
    const setup = await write(api, 'post', '/api/competitions/setup', {
      competition: {title: 'Мобильная гонка', startDate: '2026-04-01', location: 'Река', timeZone: 'UTC', admissionConfirmationRequired: false},
      categories: [{classLabel: 'R1', scoringGroupLabel: 'Открытая', crewSize: 1}], requirements: [],
      disciplines: [
        {name: 'Спринт', type: 'SPRINT', displayOrder: 0, categoryIndexes: [0], pointsTable: {name: 'Очки', tiePolicy: 'SHARED_PLACE_SAME_POINTS', rows: [{place: 1, points: 100}]}},
        {name: 'Длинная гонка', type: 'LONG_RACE', displayOrder: 1, categoryIndexes: [0]},
      ],
    })
    const competition = await setup.json()
    const categories = await (await api.get(`/api/competitions/${competition.id}/categories`)).json()
    const disciplines = await (await api.get(`/api/competitions/${competition.id}/disciplines`)).json()
    const category = categories[0], sprint = disciplines.find((d: {name: string}) => d.name === 'Спринт')
    const crewResponse = await write(api, 'post', `/api/competitions/${competition.id}/entries`, {categoryId: category.id, crewName: 'Волна', regionOrClub: 'Клуб', representative: 'Тренер', startNumber: 1, roster: [{surname: 'Иванов', givenName: 'Иван'}]})
    const crew = await crewResponse.json()
    const base = `/api/competitions/${competition.id}/disciplines/${sprint.id}/categories/${category.id}`
    await write(api, 'put', `${base}/start-list`, {random: false, crewIds: [crew.id]})
    await write(api, 'put', `${base}/crews/${crew.id}/attempts/1`, {durationCentiseconds: 7425, penaltyCentiseconds: 0, status: 'FINISHED', gates: []})
    return {competitionId: competition.id, categoryId: category.id, disciplineId: sprint.id, base, crewId: crew.id}
  } finally {await api.dispose()}
}

async function noOverflow(page: Page) {
  await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true)
}

test('Russian read-only mobile results and write denial', async ({page, baseURL}) => {
  const fixture = await seed(baseURL!)
  await page.goto(`/?lang=ru#/results?competition=${fixture.competitionId}&category=${fixture.categoryId}&discipline=${fixture.disciplineId}`)
  await page.getByLabel('Имя пользователя').fill('e2e-reader')
  await page.getByLabel('Пароль').fill('reader-password')
  await page.getByRole('button', {name: 'Войти'}).click()
  await expect(page.getByText('Только чтение')).toBeVisible()
  const crew = page.getByRole('article').filter({hasText: 'Волна'})
  await expect(crew.getByRole('heading', {name: '1 — Волна'})).toBeVisible()
  await expect(crew.getByText('Статус: Финиш')).toBeVisible()
  await expect(crew.getByText('Время: 1:14,25', {exact: true})).toBeVisible()
  await expect(crew.getByText('Очки: 100')).toBeVisible()
  await expect(page.getByRole('button', {name: 'Пересчитать'})).toHaveCount(0)
  await expect(page.getByRole('link', {name: 'Изменить результат'})).toHaveCount(0)
  await noOverflow(page)
  await page.getByRole('combobox', {name: 'Дисциплина', exact: true}).click()
  await page.getByRole('option', {name: 'Общий зачёт'}).click()
  await expect(crew.getByText('Длинная гонка: Нет результата')).toBeVisible()
  await expect(crew.getByText('Спринт: Финиш · Место: 1 · Очки: 100')).toBeVisible()
  await noOverflow(page)
  const csrfResponse = await page.request.get('/api/auth/csrf')
  expect(csrfResponse.status()).toBe(200)
  const csrf = await csrfResponse.json()
  const denied = await page.request.put(`${fixture.base}/crews/${fixture.crewId}/attempts/1`, {headers: {[csrf.headerName]: csrf.token}, data: {version: 0, durationCentiseconds: 6000, penaltyCentiseconds: 0, status: 'FINISHED', gates: []}})
  expect(denied.status()).toBe(403)
  await page.reload()
  await expect(page.getByRole('article').filter({hasText: 'Волна'}).getByText('Очки: 100')).toBeVisible()
  await expect(page.getByRole('article').filter({hasText: 'Волна'}).getByText('Время: 1:14,25', {exact: true})).toBeVisible()
})

import {expect, test, type APIRequestContext} from '@playwright/test'
import {spawnSync} from 'node:child_process'

test.setTimeout(120000)
test.use({viewport: {width: 1440, height: 900}, locale: 'en-US'})

async function get<T>(api: APIRequestContext, path: string): Promise<T> {
  const response = await api.get(path)
  expect(response.ok(), `${path}: ${response.status()}`).toBeTruthy()
  return response.json()
}
async function mutate(api: APIRequestContext, method: 'post' | 'put' | 'delete', path: string, data: object) {
  const csrf = await get<{headerName: string, token: string}>(api, '/api/auth/csrf')
  return api[method](path, {data, headers: {[csrf.headerName]: csrf.token}})
}

test('annuls one result with a reason before withdrawing admission', async ({page}) => {
  const api = page.request
  expect((await mutate(api, 'post', '/api/auth/login', {username: 'e2e-admin', password: 'e2e-password'})).ok()).toBeTruthy()
  const setup = await mutate(api, 'post', '/api/competitions/setup', {
    competition: {title: 'Admission annulment', startDate: '2026-04-01', location: 'River', timeZone: 'UTC', admissionConfirmationRequired: true},
    categories: [{classLabel: 'R1', scoringGroupLabel: 'Open', crewSize: 1}], requirements: [],
    disciplines: [{name: 'Sprint', type: 'SPRINT', displayOrder: 0, categoryIndexes: [0], pointsTable: {name: 'Points', tiePolicy: 'SHARED_PLACE_SAME_POINTS', rows: [{place: 1, points: 100}, {place: 2, points: 80}]}}],
  })
  expect(setup.ok()).toBeTruthy()
  const competition = await setup.json() as {id: number, version: number}
  const root = `/api/competitions/${competition.id}`
  expect((await mutate(api, 'put', `${root}/status`, {status: 'STARTED', version: competition.version})).ok()).toBeTruthy()
  const [category] = await get<{id: number}[]>(api, `${root}/categories`)
  const [discipline] = await get<{id: number, version: number}[]>(api, `${root}/disciplines`)
  expect((await mutate(api, 'put', `${root}/disciplines/${discipline.id}/status`, {status: 'STARTED', version: discipline.version})).ok()).toBeTruthy()
  const crews: {id: number, version: number}[] = []
  for (const [index, name] of ['F', 'A'].entries()) {
    const created = await mutate(api, 'post', `${root}/entries`, {categoryId: category.id, crewName: name, startNumber: index + 1, roster: [{surname: name, givenName: 'Racer'}]})
    expect(created.ok()).toBeTruthy()
    const crew = await created.json() as {id: number, version: number}
    const admitted = await mutate(api, 'put', `${root}/entries/${crew.id}/admission`, {admitted: true, version: crew.version})
    expect(admitted.ok()).toBeTruthy()
    crews.push(await admitted.json())
  }
  const base = `${root}/disciplines/${discipline.id}/categories/${category.id}`
  expect((await mutate(api, 'put', `${base}/start-list`, {random: false, crewIds: crews.map(c => c.id), firstStart: '2026-04-01T10:00:00Z', intervalSeconds: 60})).ok()).toBeTruthy()
  const [racingRevocation, racingResult] = await Promise.all([
    mutate(api, 'put', `${root}/entries/${crews[0].id}/admission`, {admitted: false, version: crews[0].version}),
    mutate(api, 'put', `${base}/crews/${crews[0].id}/attempts/1`, {durationCentiseconds: 100, penaltyCentiseconds: 0, status: 'FINISHED'}),
  ])
  expect([racingRevocation.status(), racingResult.status()].sort()).toEqual([200, 409])
  if (racingRevocation.ok()) {
    const withdrawn = await racingRevocation.json() as {version: number}
    const readmitted = await mutate(api, 'put', `${root}/entries/${crews[0].id}/admission`, {admitted: true, version: withdrawn.version})
    expect(readmitted.ok()).toBeTruthy()
    crews[0] = await readmitted.json()
    expect((await mutate(api, 'put', `${base}/crews/${crews[0].id}/attempts/1`, {durationCentiseconds: 100, penaltyCentiseconds: 0, status: 'FINISHED'})).ok()).toBeTruthy()
  }
  expect((await mutate(api, 'put', `${base}/crews/${crews[1].id}/attempts/1`, {durationCentiseconds: 120, penaltyCentiseconds: 0, status: 'FINISHED'})).ok()).toBeTruthy()
  expect((await mutate(api, 'put', `${root}/entries/${crews[0].id}/admission`, {admitted: false, version: crews[0].version})).status()).toBe(409)
  // Reproduce a historical inconsistent row only in this runner's disposable database.
  const container = process.env.RAFTSCORE_E2E_CONTAINER
  if (!container || !/^[a-f0-9]{64}$/.test(container) || !Number.isSafeInteger(crews[0].id)) throw new Error('Disposable database identity is required')
  const changed = spawnSync('docker', ['exec', container, 'psql', '-X', '-U', 'e2e', '-d', 'e2e', '-v', 'ON_ERROR_STOP=1', '-c', `UPDATE crew_entries SET admission_decision = false WHERE id = ${crews[0].id}`], {encoding: 'utf8'})
  expect(changed.status, changed.stderr).toBe(0)
  expect(changed.stdout).toContain('UPDATE 1')
  expect((await mutate(api, 'post', `${root}/recalculate`, {})).ok()).toBeTruthy()
  const recalculated = await get<{crewId: number, place: number, points: number}[]>(api, `${base}/results`)
  expect(recalculated.find(row => row.crewId === crews[0].id)).toBeUndefined()
  expect(recalculated.find(row => row.crewId === crews[1].id)).toMatchObject({place: 1, points: 100})
  const beforeAnnul = await get<{slots: {crewId: number}[]}>(api, `${base}/start-list`)
  expect(beforeAnnul.slots.map(slot => slot.crewId)).not.toContain(crews[0].id)
  await page.goto(`/?lang=en#/start-lists?competition=${competition.id}&discipline=${discipline.id}&category=${category.id}`)
  await page.getByLabel('Crew to annul').click()
  await page.getByRole('option', {name: 'F', exact: true}).click()
  page.on('dialog', dialog => dialog.accept(dialog.type() === 'prompt' ? 'Timing error' : undefined))
  await page.getByRole('button', {name: 'Annul attempt'}).click()
  await expect(page.getByText(/Annulled result: F — Timing error/)).toBeVisible()
  const results = await get<{crewId: number, place: number, points: number, attempts: {durationCentiseconds: number}[]}[]>(api, `${base}/results`)
  expect(results.find(r => r.crewId === crews[1].id)).toMatchObject({place: 1, points: 100, attempts: [{durationCentiseconds: 120}]})
  const history = await get<{crewId: number, reason: string, result: string}[]>(api, `${base}/start-list/annulments`)
  expect(history).toHaveLength(1)
  expect(history[0]).toMatchObject({crewId: crews[0].id, reason: 'Timing error'})
  expect(history[0].result).toContain('100')
  const revoked = await mutate(api, 'put', `${root}/entries/${crews[0].id}/admission`, {admitted: false, version: crews[0].version})
  expect(revoked.ok()).toBeTruthy()
  expect((await revoked.json()).eligible).toBe(false)
  expect((await mutate(api, 'put', `${base}/crews/${crews[0].id}/attempts/1`, {durationCentiseconds: 90, penaltyCentiseconds: 0, status: 'FINISHED'})).status()).toBe(409)
  const list = await get<{locked: boolean, slots: {crewId: number, position: number, plannedTime: string}[]}>(api, `${base}/start-list`)
  expect(list.locked).toBe(true)
  expect(list.slots).toEqual([{crewId: crews[1].id, crewName: 'A', startNumber: 2, position: 2, plannedTime: '2026-04-01T10:01:00Z'}])
  await page.reload()
  await expect(page.getByRole('cell', {name: 'A', exact: true})).toBeVisible()
  await expect(page.getByRole('cell', {name: 'F', exact: true})).toHaveCount(0)
  await expect(page.getByText(/Annulled result: F — Timing error/)).toBeVisible()
  await page.goto(`/?lang=en#/result-grid?competition=${competition.id}&discipline=${discipline.id}&category=${category.id}`)
  await page.getByText('Table entry', {exact: true}).click()
  await expect(page.getByLabel('Time A', {exact: true})).toBeVisible()
  await expect(page.getByLabel('Time F', {exact: true})).toHaveCount(0)
})

test('retains and displays an annulled heat for both participants', async ({page}) => {
  const api = page.request
  expect((await mutate(api, 'post', '/api/auth/login', {username: 'e2e-admin', password: 'e2e-password'})).ok()).toBeTruthy()
  const setup = await mutate(api, 'post', '/api/competitions/setup', {
    competition: {title: 'Heat annulment history', startDate: '2026-04-01', location: 'River', timeZone: 'UTC', admissionConfirmationRequired: true},
    categories: [{classLabel: 'R1', scoringGroupLabel: 'Open', crewSize: 1}], requirements: [],
    disciplines: [{name: 'Qualification', type: 'SPRINT', displayOrder: 0, categoryIndexes: [0]},
      {name: 'Parallel', type: 'PARALLEL_SPRINT', displayOrder: 1, categoryIndexes: [0]}],
  })
  expect(setup.ok()).toBeTruthy()
  const competition = await setup.json() as {id: number, version: number}
  const root = `/api/competitions/${competition.id}`
  expect((await mutate(api, 'put', `${root}/status`, {status: 'STARTED', version: competition.version})).ok()).toBeTruthy()
  const [category] = await get<{id: number}[]>(api, `${root}/categories`)
  const [sprint, parallel] = await get<{id: number, version: number}[]>(api, `${root}/disciplines`)
  for (const discipline of [sprint, parallel])
    expect((await mutate(api, 'put', `${root}/disciplines/${discipline.id}/status`, {status: 'STARTED', version: discipline.version})).ok()).toBeTruthy()
  const crews: {id: number, version: number, crewName: string}[] = []
  for (const [index, name] of ['First', 'Second'].entries()) {
    const created = await mutate(api, 'post', `${root}/entries`, {categoryId: category.id, crewName: name, startNumber: index + 1, roster: [{surname: name, givenName: 'Racer'}]})
    expect(created.ok()).toBeTruthy()
    const crew = await created.json() as {id: number, version: number}
    const admitted = await mutate(api, 'put', `${root}/entries/${crew.id}/admission`, {admitted: true, version: crew.version})
    expect(admitted.ok()).toBeTruthy()
    crews.push(await admitted.json())
  }
  const qualification = `${root}/disciplines/${sprint.id}/categories/${category.id}`
  const base = `${root}/disciplines/${parallel.id}/categories/${category.id}`
  expect((await mutate(api, 'put', `${qualification}/start-list`, {random: false, crewIds: crews.map(c => c.id)})).ok()).toBeTruthy()
  for (const [index, crew] of crews.entries())
    expect((await mutate(api, 'put', `${qualification}/crews/${crew.id}/attempts/1`, {durationCentiseconds: 100 + index * 20, penaltyCentiseconds: 0, status: 'FINISHED'})).ok()).toBeTruthy()
  const seeded = await mutate(api, 'post', `${base}/bracket`, {})
  expect(seeded.ok()).toBeTruthy()
  const bracket = await seeded.json() as {id: number, heats: {id: number, version: number, first: {id: number, name: string}, second: {id: number, name: string}}[]}
  const heat = bracket.heats[0]
  const recorded = await mutate(api, 'put', `${base}/brackets/${bracket.id}/heats/${heat.id}/result`, {version: heat.version, first: {status: 'FINISHED', timeCentiseconds: 100}, second: {status: 'FINISHED', timeCentiseconds: 120}})
  expect(recorded.ok()).toBeTruthy()
  await page.goto(`/?lang=en#/parallel-sprint?competition=${competition.id}&discipline=${parallel.id}&category=${category.id}`)
  page.on('dialog', dialog => dialog.accept(dialog.type() === 'prompt' ? 'Heat correction' : undefined))
  await page.getByRole('button', {name: 'Open heat 1 form', exact: true}).first().click()
  await page.getByRole('button', {name: 'Annul heat result'}).click()
  await expect.poll(async () => (await get<unknown[]>(api, `${base}/start-list/annulments`)).length).toBe(2)
  const history = await get<{crewId: number, reason: string, result: string}[]>(api, `${base}/start-list/annulments`)
  expect(history.map(row => row.crewId).sort()).toEqual([heat.first.id, heat.second.id].sort())
  for (const row of history) {
    expect(row.reason).toBe('Heat correction')
    expect(JSON.parse(row.result)).toMatchObject({crew_1_id: heat.first.id, crew_2_id: heat.second.id, time_1_centiseconds: 100, time_2_centiseconds: 120})
  }
  await page.goto(`/?lang=en#/start-lists?competition=${competition.id}&discipline=${parallel.id}&category=${category.id}`)
  await expect(page.getByText(`Annulled result: ${heat.first.name} — Heat correction`)).toBeVisible()
  await expect(page.getByText(`Annulled result: ${heat.second.name} — Heat correction`)).toBeVisible()
  const second = crews.find(crew => crew.id === heat.second.id)!
  expect((await mutate(api, 'delete', `${qualification}/start-list/source-results`, {crewId: second.id, attemptNumber: 1, version: 0, reason: 'Qualification correction'})).ok()).toBeTruthy()
  const withdrawn = await mutate(api, 'put', `${root}/entries/${second.id}/admission`, {admitted: false, version: second.version})
  expect(withdrawn.ok()).toBeTruthy()
  expect((await withdrawn.json()).eligible).toBe(false)
  await page.reload()
  await expect(page.getByText(`Annulled result: ${heat.second.name} — Heat correction`)).toBeVisible()
})

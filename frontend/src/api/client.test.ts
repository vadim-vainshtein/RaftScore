import {afterEach, expect, test, vi} from 'vitest'
import {ApiError, createApiClient} from './client'

afterEach(() => vi.unstubAllGlobals())

test('requests typed JSON with credentials and preserves request options', async () => {
  const fetchMock = vi.fn().mockResolvedValue(
    new Response(JSON.stringify({id: 7}), {
      headers: {'Content-Type': 'application/json'},
    }),
  )
  vi.stubGlobal('fetch', fetchMock)

  const request = createApiClient('/api/')
  const result = await request<{id: number}>('/competitions', {
    method: 'POST',
    headers: {'Content-Type': 'application/json'},
    body: JSON.stringify({name: 'Race'}),
  })

  expect(result.id).toBe(7)
  const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
  expect(url).toBe('/api/competitions')
  expect(init.method).toBe('POST')
  expect(init.body).toBe('{"name":"Race"}')
  expect(init.credentials).toBe('include')
  expect(new Headers(init.headers).get('Content-Type')).toBe('application/json')
  expect(new Headers(init.headers).get('Accept')).toBe('application/json')
})

test('allows credentials to be overridden', async () => {
  const fetchMock = vi.fn().mockResolvedValue(new Response(null, {status: 204}))
  vi.stubGlobal('fetch', fetchMock)

  const result = await createApiClient()('/auth', {credentials: 'omit'})

  expect(result).toBeUndefined()
  expect(fetchMock.mock.calls[0][1].credentials).toBe('omit')
})

test('ignores undocumented error text and preserves the response body', async () => {
  const body = {detail: 'Invalid entry', code: 'INVALID_ENTRY'}
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(
    new Response(JSON.stringify(body), {status: 400, statusText: 'Bad Request'}),
  ))

  await expect(createApiClient()('/entries')).rejects.toMatchObject({
    name: 'ApiError',
    status: 400,
    message: 'HTTP 400',
    code: undefined,
    parameters: undefined,
    body,
  } satisfies Partial<ApiError>)
})

test('exposes the documented error code and parameters without requiring a message', async () => {
  const body = {
    code: 'entry.participant_already_registered',
    parameters: {participantName: 'Alex Smith'},
  }
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(
    new Response(JSON.stringify(body), {status: 409, statusText: 'Conflict'}),
  ))

  await expect(createApiClient()('/entries')).rejects.toMatchObject({
    status: 409,
    message: 'HTTP 409',
    code: body.code,
    parameters: body.parameters,
    body,
  } satisfies Partial<ApiError>)
})

test('ignores malformed error code and parameters', async () => {
  const body = {code: 123, parameters: {participantName: 'Alex Smith'}}
  const malformedParameters = {code: 'INVALID_ENTRY', parameters: []}
  const fetchMock = vi.fn()
    .mockResolvedValueOnce(new Response(JSON.stringify(body), {status: 400}))
    .mockResolvedValueOnce(new Response(JSON.stringify(malformedParameters), {status: 400}))
  vi.stubGlobal('fetch', fetchMock)
  const request = createApiClient()

  await expect(request('/entries')).rejects.toMatchObject({
    message: 'HTTP 400',
    body,
    code: undefined,
    parameters: undefined,
  } satisfies Partial<ApiError>)
  await expect(request('/entries')).rejects.toMatchObject({
    message: 'HTTP 400',
    body: malformedParameters,
    code: undefined,
    parameters: undefined,
  } satisfies Partial<ApiError>)
})

test('uses a generic error message for plain text and empty error bodies', async () => {
  const fetchMock = vi.fn()
    .mockResolvedValueOnce(new Response('Unavailable', {status: 503}))
    .mockResolvedValueOnce(new Response(null, {status: 500, statusText: 'Server Error'}))
  vi.stubGlobal('fetch', fetchMock)
  const request = createApiClient()

  await expect(request('/first')).rejects.toMatchObject({
    status: 503, message: 'HTTP 503', body: 'Unavailable',
  })
  await expect(request('/second')).rejects.toMatchObject({
    status: 500, message: 'HTTP 500', body: undefined,
  })
})

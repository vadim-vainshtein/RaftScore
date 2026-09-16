export class ApiError extends Error {
  readonly status: number
  readonly body: unknown
  readonly code?: string
  readonly parameters?: Record<string, unknown>

  constructor(
    status: number,
    body: unknown,
  ) {
    super(`HTTP ${status}`)
    this.name = 'ApiError'
    this.status = status
    this.body = body
    if (isRaftScoreApiError(body)) {
      this.code = body.code
      this.parameters = body.parameters
    }
  }
}

type RaftScoreApiError = {
  code: string
  parameters: Record<string, unknown>
}

function isRaftScoreApiError(value: unknown): value is RaftScoreApiError {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) return false
  const body = value as Record<string, unknown>
  const parameters = body.parameters
  return typeof body.code === 'string' && !!body.code.trim()
    && parameters !== null && typeof parameters === 'object' && !Array.isArray(parameters)
}

export function createApiClient(baseUrl = '') {

  return async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
    const url = buildUrl(path)
    const headers = buildHeaders(init);

    const response = await queryApi(url, init, headers)
    const body = await parseBody(response);

    validateResponseOk(response, body);

    return body as T
  }

  function buildUrl(path: string) {
    return baseUrl
        ? `${baseUrl.replace(/\/$/, '')}/${path.replace(/^\//, '')}`
        : path;
  }

  function buildHeaders(init: RequestInit) {
    const headers = new Headers(init.headers)
    if (!headers.has('Accept')) headers.set('Accept', 'application/json')
    return headers;
  }

  async function queryApi(url: string, init: RequestInit, headers: Headers) {
    return await fetch(url, {
      ...init,
      headers,
      credentials: init.credentials ?? 'include',
    });
  }

  async function parseBody(response: Response) {
    const text = await response.text()
    let body: unknown = text || undefined
    if (text) {
      try {
        body = JSON.parse(text) as unknown
      } catch {
        if (response.ok) throw new SyntaxError('Invalid JSON response')
      }
    }
    return body;
  }

  function validateResponseOk(response: Response, body: unknown) {
    if (!response.ok) {
      throw new ApiError(response.status, body)
    }
  }
}

export const apiRequest = createApiClient()

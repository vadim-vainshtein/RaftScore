// Run only against a fresh, unnamed Docker volume and a server started by this process.
import { spawn, spawnSync } from 'node:child_process'
import { createServer } from 'node:net'
import { createInterface } from 'node:readline'
import { setTimeout as delay } from 'node:timers/promises'
import { fileURLToPath } from 'node:url'
import path from 'node:path'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..')
const jar = path.join(root, 'target', 'raft-score-0.0.1-SNAPSHOT.jar')
const freePort = await new Promise((resolve, reject) => {
  const server = createServer().listen(0, '127.0.0.1', () => {
    const port = server.address().port
    server.close(() => resolve(port))
  })
  server.on('error', reject)
})
const docker = (...args) => {
  const result = spawnSync('docker', args, { encoding: 'utf8' })
  if (result.status !== 0) throw new Error(`docker ${args[0]} failed: ${result.stderr || result.error}`)
  return result.stdout.trim()
}
let container, app, tests, interrupted = false
for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => {
  interrupted = true
  if (tests && tests.exitCode === null) tests.kill()
  if (app && app.exitCode === null) app.kill()
})
try {
  container = docker('run', '-d', '--rm', '-e', 'POSTGRES_DB=e2e', '-e', 'POSTGRES_USER=e2e', '-e', 'POSTGRES_PASSWORD=e2e-password', '-p', '127.0.0.1::5432', 'postgres:17-alpine')
  console.log(`Disposable PostgreSQL container: ${container}`)
  const mapping = docker('port', container, '5432/tcp')
  const dbPort = mapping.match(/:(\d+)$/)?.[1]
  if (!dbPort) throw new Error(`Unexpected Docker port mapping: ${mapping}`)
  for (let i = 0; ; i++) {
    if (spawnSync('docker', ['exec', container, 'pg_isready', '-U', 'e2e', '-d', 'e2e']).status === 0) break
    if (interrupted) throw new Error('Interrupted')
    if (i === 60) throw new Error('PostgreSQL did not become ready')
    await delay(1000)
  }
  const url = `http://127.0.0.1:${freePort}`
  // Ignore ambient Spring configuration so this process can only reach its own database.
  const appEnv = Object.fromEntries(Object.entries(process.env).filter(([key]) => !/^(SPRING_|SERVER_|RAFT_SCORE_|JAVA_TOOL_OPTIONS|JDK_JAVA_OPTIONS)/i.test(key)))
  let started = false
  app = spawn('java', ['-jar', jar,
    `--spring.datasource.url=jdbc:postgresql://127.0.0.1:${dbPort}/e2e`, '--spring.datasource.username=e2e', '--spring.datasource.password=e2e-password',
    '--raftscore.initial-administrator.username=e2e-admin', '--raftscore.initial-administrator.password=e2e-password',
    '--server.address=127.0.0.1', `--server.port=${freePort}`,
  ], { cwd: root, stdio: ['ignore', 'pipe', 'inherit'], env: appEnv })
  createInterface({ input: app.stdout }).on('line', line => {
    console.log(line)
    if (line.includes('Started RaftScoreApplication')) started = true
  })
  app.on('error', error => console.error(error))
  for (let i = 0; !started; i++) {
    if (interrupted) throw new Error('Interrupted')
    if (app.exitCode !== null) throw new Error(`Application exited: ${app.exitCode}`)
    if (i === 120) throw new Error('Application did not become ready')
    await delay(1000)
  }
  tests = spawn(process.execPath, [path.join(root, 'frontend/node_modules/@playwright/test/cli.js'), 'test', ...process.argv.slice(2)], {
    cwd: path.join(root, 'frontend'), stdio: 'inherit', env: {...process.env, RAFTSCORE_E2E_URL: url},
  })
  process.exitCode = await new Promise(resolve => {
    tests.on('error', () => resolve(1))
    tests.on('exit', code => resolve(code ?? 1))
  })
} finally {
  if (app && app.exitCode === null) app.kill()
  if (container) docker('rm', '-f', '-v', container)
}

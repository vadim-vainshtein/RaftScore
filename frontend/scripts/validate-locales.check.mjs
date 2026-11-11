import {test} from 'node:test'
import assert from 'node:assert/strict'
import {mkdtemp, mkdir, rm, writeFile} from 'node:fs/promises'
import {tmpdir} from 'node:os'
import {dirname, join} from 'node:path'
import {fileURLToPath} from 'node:url'
import {validateCatalogs} from './validate-locales.mjs'

const actual = join(dirname(fileURLToPath(import.meta.url)), '../src/i18n/locales')
const en = {items_one: '{{count}} item', items_other: '{{count}} items', greeting: 'Hello {{name}}'}
const ru = {items_one: '{{count}}', items_few: '{{count}}', items_many: '{{count}}', items_other: '{{count}}', greeting: 'Hi {{name}}'}

async function fixture(callback) {
  const root = await mkdtemp(join(tmpdir(), 'raftscore-locales-'))
  try {
    await Promise.all(['en', 'ru'].map(locale => mkdir(join(root, locale))))
    await writeFile(join(root, 'en/common.json'), JSON.stringify(en))
    await writeFile(join(root, 'ru/common.json'), JSON.stringify(ru))
    await callback(root)
  } finally { await rm(root, {recursive: true, force: true}) }
}

test('actual catalogs and valid plural families pass', async () => {
  assert.deepEqual(await validateCatalogs(actual), [])
  await fixture(async root => assert.deepEqual(await validateCatalogs(root), []))
})

test('rejects malformed JSON and namespace mismatches', async () => fixture(async root => {
  await writeFile(join(root, 'ru/common.json'), '{')
  await writeFile(join(root, 'ru/extra.json'), '{}')
  const errors = await validateCatalogs(root)
  assert.ok(errors.some(error => error.includes('ru/common.json')))
  assert.ok(errors.some(error => error.includes('extra.json') && error.includes('extra')))
}))

test('rejects missing keys, plural variants and differing parameters', async () => fixture(async root => {
  await writeFile(join(root, 'ru/common.json'), JSON.stringify({
    items_one: '{{count}}', items_many: '{{count}}', items_other: '{{wrong}}', extra: 'extra',
  }))
  const errors = await validateCatalogs(root)
  assert.ok(errors.some(error => error.includes('missing plural variant few')))
  assert.ok(errors.some(error => error.includes('differing interpolation parameters')))
  assert.ok(errors.some(error => error.includes('greeting missing')))
  assert.ok(errors.some(error => error.includes('extra extra')))
}))

test('rejects interpolation differences between locales', async () => fixture(async root => {
  await writeFile(join(root, 'ru/common.json'), JSON.stringify({...ru, greeting: 'Hi {{person}}'}))
  assert.ok((await validateCatalogs(root)).some(error => error.includes('greeting has differing interpolation parameters')))
}))

test('rejects unexpected plural variants and empty locale directories', async () => fixture(async root => {
  await writeFile(join(root, 'en/common.json'), JSON.stringify({...en, items_few: '{{count}}'}))
  assert.ok((await validateCatalogs(root)).some(error => error.includes('items_few unexpected plural variant')))
  await rm(join(root, 'en/common.json'))
  await rm(join(root, 'ru/common.json'))
  const errors = await validateCatalogs(root)
  assert.ok(errors.some(error => error.includes('en: no JSON namespaces')))
  assert.ok(errors.some(error => error.includes('ru: no JSON namespaces')))
}))

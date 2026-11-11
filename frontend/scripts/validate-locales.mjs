import {readdir, readFile} from 'node:fs/promises'
import {dirname, join, resolve} from 'node:path'
import {fileURLToPath} from 'node:url'

const pluralSuffixes = new Set(['zero', 'one', 'two', 'few', 'many', 'other'])
const supportedLocales = ['en', 'ru']

function parameters(value) {
  return [...new Set([...value.matchAll(/{{\s*(-\s*)?([^},\s]+)(?:\s*,[^}]*)?\s*}}/g)].map(match => match[2]))].sort().join(', ')
}

function flatten(value, path = [], result = new Map()) {
  if (typeof value === 'string') {
    result.set(path.join('.'), value)
  } else if (value && typeof value === 'object' && !Array.isArray(value) && Object.keys(value).length) {
    for (const [key, child] of Object.entries(value)) flatten(child, [...path, key], result)
  } else {
    throw new Error(`${path.join('.') || '<root>'}: expected a string or non-empty object`)
  }
  return result
}

function families(catalog, locale, namespace, errors) {
  const entries = flatten(catalog)
  const result = new Map()
  for (const [key, value] of entries) {
    const match = key.match(/^(.*)_(zero|one|two|few|many|other)$/)
    const family = match && pluralSuffixes.has(match[2]) ? match[1] : key
    const variant = match ? match[2] : ''
    if (!result.has(family)) result.set(family, new Map())
    result.get(family).set(variant, parameters(value))
  }
  for (const [key, variants] of result) {
    if (variants.has('') && variants.size > 1) errors.push(`${locale}/${namespace}: ${key} mixes plural and ordinary keys`)
    if (!variants.has('')) {
      const categories = new Intl.PluralRules(locale).resolvedOptions().pluralCategories
      for (const variant of variants.keys()) {
        if (variant !== 'zero' && !categories.includes(variant)) errors.push(`${locale}/${namespace}: ${key}_${variant} unexpected plural variant`)
      }
      for (const category of categories) {
        if (!variants.has(category)) errors.push(`${locale}/${namespace}: ${key} missing plural variant ${category}`)
      }
    }
    const expected = variants.values().next().value
    for (const [variant, params] of variants) {
      if (params !== expected) errors.push(`${locale}/${namespace}: ${key}${variant ? `_${variant}` : ''} has differing interpolation parameters`)
    }
  }
  return result
}

export async function validateCatalogs(root, locales = supportedLocales) {
  const errors = []
  const catalogs = new Map()
  for (const locale of locales) {
    let files
    try { files = await readdir(join(root, locale)) } catch (error) {
      errors.push(`${locale}: ${error.message}`)
      continue
    }
    if (!files.some(name => name.endsWith('.json'))) errors.push(`${locale}: no JSON namespaces`)
    const namespaces = new Map()
    for (const file of files.filter(name => name.endsWith('.json')).sort()) {
      try {
        const json = JSON.parse(await readFile(join(root, locale, file), 'utf8'))
        namespaces.set(file, families(json, locale, file, errors))
      } catch (error) { errors.push(`${locale}/${file}: ${error.message}`) }
    }
    catalogs.set(locale, namespaces)
  }
  const reference = catalogs.get(locales[0])
  if (reference) for (const locale of locales.slice(1)) {
    const current = catalogs.get(locale)
    if (!current) continue
    for (const namespace of new Set([...reference.keys(), ...current.keys()])) {
      if (!reference.has(namespace) || !current.has(namespace)) {
        errors.push(`${locale}/${namespace}: namespace ${reference.has(namespace) ? 'missing' : 'extra'}`)
        continue
      }
      const base = reference.get(namespace)
      const translated = current.get(namespace)
      for (const key of new Set([...base.keys(), ...translated.keys()])) {
        if (!base.has(key) || !translated.has(key)) {
          errors.push(`${locale}/${namespace}: ${key} ${base.has(key) ? 'missing' : 'extra'}`)
        } else if (base.get(key).has('') !== translated.get(key).has('')) {
          errors.push(`${locale}/${namespace}: ${key} plural structure differs`)
        } else if (base.get(key).values().next().value !== translated.get(key).values().next().value) {
          errors.push(`${locale}/${namespace}: ${key} has differing interpolation parameters`)
        }
      }
    }
  }
  return errors
}

const script = fileURLToPath(import.meta.url)
if (process.argv[1] && resolve(process.argv[1]) === script) {
  const errors = await validateCatalogs(join(dirname(script), '../src/i18n/locales'))
  if (errors.length) {
    console.error(`Locale validation failed:\n${errors.join('\n')}`)
    process.exitCode = 1
  } else console.log('Locale catalogs are valid.')
}

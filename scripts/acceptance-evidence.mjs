import fs from 'node:fs/promises'
import path from 'node:path'

/** The latest attempt is authoritative, including failures and incomplete runs. */
export async function latestResult(directory, prefix) {
  const names = (await fs.readdir(directory)).filter(name => name.startsWith(prefix)).sort().reverse()
  if (!names.length) throw new Error('No ' + prefix + ' browser evidence')
  const name = names[0]
  let result
  try { result = JSON.parse(await fs.readFile(path.join(directory, name, 'result.json'), 'utf8')) }
  catch { throw new Error('Latest browser attempt is incomplete or unreadable: ' + name) }
  if (result.success !== true) throw new Error('Latest browser attempt failed: ' + name)
  if (!Array.isArray(result.steps) || result.steps.length === 0 || !Array.isArray(result.pageErrors) || result.pageErrors.length) {
    throw new Error('Latest browser evidence is invalid: ' + name)
  }
  return { name, result }
}

import test from 'node:test'
import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import fs from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import { createHash } from 'node:crypto'
import { fileURLToPath } from 'node:url'

const powershell = ['powershell', 'pwsh'].find(command => spawnSync(command, ['-NoProfile', '-Command', '$PSVersionTable.PSVersion.ToString()'], { encoding: 'utf8' }).status === 0)
const verifierSource = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../scripts/Verify-AcceptanceBundle.ps1')
const hash = value => createHash('sha256').update(value).digest('hex')

test('acceptance bundle verifier rejects paths outside its root', { skip: !powershell && 'PowerShell is unavailable' }, async () => {
  const temporary = await fs.mkdtemp(path.join(os.tmpdir(), 'acceptance-verifier-'))
  const bundle = path.join(temporary, 'bundle')
  const outside = path.join(temporary, 'outside.txt')
  try {
    await fs.mkdir(path.join(bundle, 'scripts'), { recursive: true })
    await fs.copyFile(verifierSource, path.join(bundle, 'scripts/Verify-AcceptanceBundle.ps1'))
    await fs.writeFile(outside, 'outside')
    const manifestPath = path.join(bundle, 'manifest.json')
    const invoke = () => spawnSync(powershell, ['-NoProfile', '-File', path.join(bundle, 'scripts/Verify-AcceptanceBundle.ps1'), '-BundleRoot', bundle], { encoding: 'utf8' })

    await fs.writeFile(manifestPath, JSON.stringify({ files: [{ path: '../outside.txt', bytes: 7, sha256: hash('outside') }] }))
    const traversal = invoke()
    assert.notEqual(traversal.status, 0)
    assert.match(traversal.stderr + traversal.stdout, /escapes the bundle/i)

    await fs.writeFile(manifestPath, JSON.stringify({ files: [{ path: outside, bytes: 7, sha256: hash('outside') }] }))
    assert.notEqual(invoke().status, 0)

    await fs.writeFile(manifestPath, JSON.stringify({ files: [] }))
    assert.notEqual(invoke().status, 0)

    await fs.writeFile(path.join(bundle, 'inside.txt'), 'inside')
    await fs.writeFile(manifestPath, JSON.stringify({ files: [{ path: 'inside.txt', bytes: 6, sha256: hash('inside') }] }))
    const valid = invoke()
    assert.equal(valid.status, 0, valid.stderr + valid.stdout)
  } finally {
    await fs.rm(temporary, { recursive: true, force: true })
  }

test('acceptance bundle verifier rejects symlink traversal when the platform supports junctions', { skip: !powershell && 'PowerShell is unavailable' }, async context => {
  const temporary = await fs.mkdtemp(path.join(os.tmpdir(), 'acceptance-verifier-link-'))
  const bundle = path.join(temporary, 'bundle')
  const outside = path.join(temporary, 'outside')
  try {
    await fs.mkdir(path.join(bundle, 'scripts'), { recursive: true })
    await fs.mkdir(outside)
    await fs.copyFile(verifierSource, path.join(bundle, 'scripts/Verify-AcceptanceBundle.ps1'))
    await fs.writeFile(path.join(outside, 'secret.txt'), 'outside')
    try {
      await fs.symlink(outside, path.join(bundle, 'linked'), 'junction')
    } catch (error) {
      if (['EPERM', 'EACCES', 'ENOTSUP'].includes(error.code)) { context.skip('Junction creation is not permitted'); return }
      throw error
    }
    await fs.writeFile(path.join(bundle, 'manifest.json'), JSON.stringify({ files: [{ path: 'linked/secret.txt', bytes: 7, sha256: hash('outside') }] }))
    const result = spawnSync(powershell, ['-NoProfile', '-File', path.join(bundle, 'scripts/Verify-AcceptanceBundle.ps1'), '-BundleRoot', bundle], { encoding: 'utf8' })
    assert.notEqual(result.status, 0)
    assert.match(result.stderr + result.stdout, /traverses a link/i)
  } finally {
    await fs.rm(temporary, { recursive: true, force: true })
  }
})

})

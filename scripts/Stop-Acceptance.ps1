$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$sessionFile = Join-Path $projectRoot 'data/acceptance/current-session.json'
if (-not (Test-Path -LiteralPath $sessionFile)) { Write-Output 'No recorded acceptance session.'; return }
$session = Get-Content -Raw -LiteralPath $sessionFile | ConvertFrom-Json
foreach ($entry in @(@{ Id = $session.proxyPid; Kind = 'proxy' }, @{ Id = $session.backendPid; Kind = 'backend' })) {
    $ownedProcess = Get-CimInstance Win32_Process -Filter "ProcessId = $($entry.Id)" -ErrorAction SilentlyContinue
    if (-not $ownedProcess) { continue }
    $expected = if ($entry.Kind -eq 'proxy') { 'acceptance-server.mjs' } else { "-Dacceptance.session=$($session.session)" }
    if (-not $ownedProcess.CommandLine.Contains($expected)) { throw "PID $($entry.Id) no longer matches the acceptance session; no process was stopped for this entry." }
    Stop-Process -Id $entry.Id -ErrorAction Stop
    Write-Output "Stopped acceptance $($entry.Kind) PID $($entry.Id)"
}

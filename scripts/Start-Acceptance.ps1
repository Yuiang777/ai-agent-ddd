param(
    [switch]$SkipBuild,
    [ValidateRange(1025, 65535)][int]$Port = 4173,
    [ValidateRange(1025, 65535)][int]$BackendPort = 18091,
    [ValidateSet('LAWYER', 'PARALEGAL')][string]$Role = 'LAWYER'
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Push-Location $projectRoot
try {
    if ($Port -eq $BackendPort) { throw 'Frontend and backend ports must be different' }
    foreach ($candidate in @($Port, $BackendPort)) {
        $probe = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, $candidate)
        try { $probe.Start() }
        catch { throw "Acceptance port $candidate is already in use. Stop the recorded acceptance session or choose different ports." }
        finally { $probe.Stop() }
    }
    if (-not $SkipBuild) {
        & npm --prefix front run build
        if ($LASTEXITCODE -ne 0) { throw 'Frontend build failed' }
        & mvn -B -pl ddd-agent-sheep-app -am package
        if ($LASTEXITCODE -ne 0) { throw 'Backend build or tests failed' }
    }
    & node (Join-Path $PSScriptRoot 'acceptance-server.mjs') "--port=$Port" "--backend-port=$BackendPort" "--role=$Role"
    if ($LASTEXITCODE -ne 0) { throw 'Acceptance service exited with an error; inspect the printed session log' }
} finally {
    Pop-Location
}

param([string]$BundleRoot = (Split-Path -Parent $PSScriptRoot))
$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath($BundleRoot).TrimEnd([IO.Path]::DirectorySeparatorChar, [IO.Path]::AltDirectorySeparatorChar)
$rootPrefix = $root + [IO.Path]::DirectorySeparatorChar
$manifestPath = Join-Path $root 'manifest.json'
$manifest = Get-Content -Raw -Encoding UTF8 -LiteralPath $manifestPath | ConvertFrom-Json
if (@($manifest.files).Count -eq 0) { throw 'Manifest contains no frozen artifacts.' }
$seen = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
foreach ($file in $manifest.files) {
    if ([string]::IsNullOrWhiteSpace([string]$file.path) -or $file.sha256 -notmatch '^[a-fA-F0-9]{64}$' -or [string]$file.bytes -notmatch '^[0-9]+$') {
        throw 'Manifest contains an invalid artifact entry.'
    }
    $relativePath = ([string]$file.path).Replace('/', [IO.Path]::DirectorySeparatorChar)
    if ([IO.Path]::IsPathRooted($relativePath)) { throw "Artifact path must be relative: $($file.path)" }
    $fullPath = [IO.Path]::GetFullPath((Join-Path $root $relativePath))
    if (-not $fullPath.StartsWith($rootPrefix, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Artifact path escapes the bundle: $($file.path)"
    }
    if (-not $seen.Add($fullPath)) { throw "Duplicate artifact path: $($file.path)" }
    $currentPath = $root
    $relativeUnderRoot = $fullPath.Substring($rootPrefix.Length)
    foreach ($part in $relativeUnderRoot.Split([IO.Path]::DirectorySeparatorChar)) {
        $currentPath = Join-Path $currentPath $part
        $component = Get-Item -Force -LiteralPath $currentPath
        if (($component.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { throw "Artifact path traverses a link: $($file.path)" }
    }
    $item = Get-Item -LiteralPath $fullPath
    if ($item.PSIsContainer -or $item.Length -ne $file.bytes) { throw "Artifact size or type changed: $($file.path)" }
    $actual = (Get-FileHash -Algorithm SHA256 -LiteralPath $fullPath).Hash.ToLowerInvariant()
    if ($actual -ne $file.sha256.ToLowerInvariant()) { throw "Artifact changed: $($file.path)" }
}
Write-Output "Verified $($manifest.files.Count) frozen artifact files. Runtime data is not part of the frozen files."

param(
    [Parameter(Mandatory = $true)][string]$InputDirectory,
    [Parameter(Mandatory = $true)][string]$OutputFile
)

$ErrorActionPreference = 'Stop'
$source = (Resolve-Path -LiteralPath $InputDirectory).Path
$parts = @(
    @{ Pattern = 'origin-*-npatched.apk'; Entry = 'base.apk' },
    @{ Pattern = 'split_config.arm64_v8a-*-npatched.apk'; Entry = 'split_config.arm64_v8a.apk' },
    @{ Pattern = 'split_config.xxxhdpi-*-npatched.apk'; Entry = 'split_config.xxxhdpi.apk' }
)
foreach ($part in $parts) {
    $matches = @(Get-ChildItem -LiteralPath $source -Filter $part.Pattern -File)
    if ($matches.Count -ne 1) { throw "Expected one $($part.Pattern) in $source; found $($matches.Count)" }
    $part.Path = $matches[0].FullName
}

$target = [System.IO.Path]::GetFullPath($OutputFile)
$parent = [System.IO.Path]::GetDirectoryName($target)
[System.IO.Directory]::CreateDirectory($parent) | Out-Null
if (Test-Path -LiteralPath $target) { throw "Output already exists: $target" }

Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$stream = [System.IO.File]::Open($target, [System.IO.FileMode]::CreateNew)
try {
    $archive = [System.IO.Compression.ZipArchive]::new($stream, [System.IO.Compression.ZipArchiveMode]::Create, $true)
    try {
        foreach ($part in $parts) {
            [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
                $archive, $part.Path, $part.Entry,
                [System.IO.Compression.CompressionLevel]::NoCompression
            ) | Out-Null
        }
    } finally { $archive.Dispose() }
} finally { $stream.Dispose() }

$digest = (Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash
"$digest  $([System.IO.Path]::GetFileName($target))" |
    Set-Content -LiteralPath "$target.sha256" -Encoding ascii
Write-Output "Created $target"
Write-Output "SHA-256 $digest"

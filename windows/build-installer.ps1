$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$appProject = Join-Path $PSScriptRoot 'Salom.Desktop\Salom.Desktop.csproj'
$appPublish = Join-Path $PSScriptRoot 'Salom.Desktop\artifacts\publish'
$setupProject = Join-Path $PSScriptRoot 'Salom.Setup\Salom.Setup.csproj'
$payloadDir = Join-Path $PSScriptRoot 'Salom.Setup\payload'
$payload = Join-Path $payloadDir 'SalomDesktop.zip'
$installerOut = Join-Path $repo 'outputs\SALOM-0.15.1-AllowCookie-Setup.exe'
New-Item -ItemType Directory -Force -Path $payloadDir,(Split-Path -Parent $installerOut) | Out-Null
dotnet publish $appProject -c Release -r win-x64 --self-contained true --no-restore -p:PublishSingleFile=true -p:IncludeNativeLibrariesForSelfExtract=true -p:EnableCompressionInSingleFile=true -o $appPublish
if ($LASTEXITCODE -ne 0) { throw 'Desktop application publish failed.' }
if (Test-Path -LiteralPath $payload) { Remove-Item -LiteralPath $payload -Force }
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [IO.Compression.ZipFile]::Open($payload,[IO.Compression.ZipArchiveMode]::Create)
try {
    Get-ChildItem -LiteralPath $appPublish -File -Recurse | Where-Object { $_.Extension -notin '.pdb','.xml' } | ForEach-Object {
        $relative = [IO.Path]::GetRelativePath($appPublish,$_.FullName).Replace('\','/')
        [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive,$_.FullName,$relative,[IO.Compression.CompressionLevel]::Optimal) | Out-Null
    }
}
finally { $archive.Dispose() }
dotnet publish $setupProject -c Release -r win-x64 --self-contained true -p:PublishSingleFile=true -p:IncludeNativeLibrariesForSelfExtract=true -p:EnableCompressionInSingleFile=true -o (Join-Path $PSScriptRoot 'Salom.Setup\artifacts')
if ($LASTEXITCODE -ne 0) { throw 'Installer build failed.' }
$built = Join-Path $PSScriptRoot 'Salom.Setup\artifacts\Salom.Setup.exe'
if (-not (Test-Path -LiteralPath $built)) { throw 'Installer executable is missing.' }
Copy-Item -LiteralPath $built -Destination $installerOut -Force
Write-Output "Installer: $installerOut"

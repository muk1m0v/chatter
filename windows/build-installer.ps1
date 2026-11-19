param([string]$GoogleOAuthJsonPath)
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$appProject = Join-Path $PSScriptRoot 'Salom.Desktop\Salom.Desktop.csproj'
$appPublish = Join-Path $PSScriptRoot 'Salom.Desktop\artifacts\publish'
$setupProject = Join-Path $PSScriptRoot 'Salom.Setup\Salom.Setup.csproj'
$payloadDir = Join-Path $PSScriptRoot 'Salom.Setup\payload'
$payload = Join-Path $payloadDir 'SalomDesktop.zip'
$installerOut = Join-Path $repo 'outputs\SALOM-0.15.1-AllowCookie-Setup.exe'
New-Item -ItemType Directory -Force -Path $payloadDir,(Split-Path -Parent $installerOut) | Out-Null
$googleOAuth = Get-Content -LiteralPath $GoogleOAuthJsonPath -Raw | ConvertFrom-Json
$desktopOAuth = $googleOAuth.installed
if (-not $desktopOAuth.client_id -or -not $desktopOAuth.client_secret) { throw 'The supplied file is not a Google Desktop OAuth client JSON.' }
$firebasePath = Join-Path $repo 'android\app\google-services.json'
$firebase = Get-Content -LiteralPath $firebasePath -Raw | ConvertFrom-Json
$firebaseClient = $firebase.client | Where-Object { $_.client_info.android_client_info.package_name -eq 'com.mukimov.salom' } | Select-Object -First 1
$firebaseKey = $firebaseClient.api_key[0].current_key
if (-not $firebase.project_info.project_id -or -not $firebaseKey) { throw 'SALOM Firebase project/API key configuration is missing.' }
$oauthConfigPath = Join-Path ([IO.Path]::GetTempPath()) ('salom.oauth.' + [guid]::NewGuid().ToString('N') + '.json')
$oauthConfig = [ordered]@{ googleClientId=$desktopOAuth.client_id; googleClientSecret=$desktopOAuth.client_secret; firebaseWebApiKey=$firebaseKey }
[IO.File]::WriteAllText($oauthConfigPath, ($oauthConfig | ConvertTo-Json -Compress), [Text.UTF8Encoding]::new($false))
Write-Output "Using existing Google Desktop OAuth client and Firebase project $($firebase.project_info.project_id)."
try {
dotnet publish $appProject -c Release -r win-x64 --self-contained true --no-restore -p:PublishSingleFile=true -p:IncludeNativeLibrariesForSelfExtract=true -p:EnableCompressionInSingleFile=true "-p:SalomOAuthConfigPath=$oauthConfigPath" -o $appPublish
if ($LASTEXITCODE -ne 0) { throw 'Desktop application publish failed.' }
}
finally { Remove-Item -LiteralPath $oauthConfigPath -Force -ErrorAction SilentlyContinue }
if (Test-Path -LiteralPath $payload) { Remove-Item -LiteralPath $payload -Force }
Add-Type -AssemblyName System.IO.Compression.FileSystem
Add-Type -AssemblyName System.IO.Compression
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

$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$signingRoot = Join-Path $env:LOCALAPPDATA 'BlueFatFish\signing'
New-Item -ItemType Directory -Path $signingRoot -Force | Out-Null
$keystorePath = Join-Path $signingRoot 'release.jks'
$passwordPath = Join-Path $signingRoot 'password.txt'
if (-not (Test-Path -LiteralPath $keystorePath)) {
    $bytes = New-Object byte[] 32
    [Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
    $signingPassword = [Convert]::ToBase64String($bytes)
    [IO.File]::WriteAllText($passwordPath, $signingPassword)
    & keytool -genkeypair -keystore $keystorePath -storetype PKCS12 -storepass:file $passwordPath -keypass:file $passwordPath -alias bluefish -keyalg RSA -keysize 3072 -validity 10000 -dname 'CN=Blue Fat Fish, OU=Personal Android App'
    if ($LASTEXITCODE -ne 0) { throw 'Release keystore generation failed.' }
}
if (-not (Test-Path -LiteralPath $passwordPath)) { throw 'The existing release signing password is missing. Do not replace the keystore.' }
$signingPassword = [IO.File]::ReadAllText($passwordPath).Trim()
$escapedStore = $keystorePath.Replace('\','/').Replace(':','\:')
$properties = "storeFile=$escapedStore`nstorePassword=$signingPassword`nkeyAlias=bluefish`nkeyPassword=$signingPassword`n"
[IO.File]::WriteAllText((Join-Path $projectRoot 'signing.properties'),$properties)
Push-Location $projectRoot
try {
    & .\gradlew.bat :app:assembleRelease :app:lintRelease --console=plain
    if ($LASTEXITCODE -ne 0) { throw 'Release build failed.' }
    $outputRoot = Join-Path $projectRoot 'output'
    New-Item -ItemType Directory -Path $outputRoot -Force | Out-Null
    Copy-Item -LiteralPath (Join-Path $projectRoot 'app\build\outputs\apk\release\app-release.apk') -Destination (Join-Path $outputRoot 'lanse-dafeiyu-0.1.0.apk')
    Write-Output 'Signed APK: output\lanse-dafeiyu-0.1.0.apk'
} finally { Pop-Location; $signingPassword = $null; $properties = $null }

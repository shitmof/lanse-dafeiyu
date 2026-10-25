param([string]$SigningDirectory = (Join-Path $env:LOCALAPPDATA 'BlueFatFish\signing'))
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$signingRoot = [IO.Path]::GetFullPath($SigningDirectory)
$keystorePath = Join-Path $signingRoot 'release.jks'
$passwordPath = Join-Path $signingRoot 'password.txt'
if (-not (Test-Path -LiteralPath $keystorePath)) {
    throw 'Original key is not visible. Set -SigningDirectory to the existing protected key. This script never creates or replaces keys.'
}
if (-not (Test-Path -LiteralPath $passwordPath)) { throw 'The existing release signing password is missing. Do not replace the keystore.' }
$keytool = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\keytool.exe' } else { 'keytool' }
$certificate = & $keytool -list -v -keystore $keystorePath -storepass:file $passwordPath -alias bluefish 2>&1
if ($LASTEXITCODE -ne 0) { throw 'Cannot read the original signing certificate.' }
$expectedCertificate = '5BE09C6129CA809F1784576D76F6407A8C5949075E2D25693BAACB5DFF4282EE'
if (!(($certificate -join '').Replace(':','').ToUpperInvariant().Contains($expectedCertificate))) {
    throw 'Certificate differs from the original 0.1.0 release. Refusing an incompatible update.'
}
$signingPassword = [IO.File]::ReadAllText($passwordPath).Trim()
$escapedStore = $keystorePath.Replace('\','/').Replace(':','\:')
$properties = "storeFile=$escapedStore`nstorePassword=$signingPassword`nkeyAlias=bluefish`nkeyPassword=$signingPassword`n"
[IO.File]::WriteAllText((Join-Path $projectRoot 'signing.properties'),$properties)
$versionMatch = [regex]::Match([IO.File]::ReadAllText((Join-Path $projectRoot 'app\build.gradle')), "versionName\s+'([^']+)'")
if (!$versionMatch.Success) { throw 'Cannot determine release version.' }
$version = $versionMatch.Groups[1].Value
Push-Location $projectRoot
try {
    & .\gradlew.bat :app:assembleRelease :app:lintRelease --no-daemon --console=plain
    if ($LASTEXITCODE -ne 0) { throw 'Release build failed.' }
    $outputRoot = Join-Path $projectRoot "output\release-$version"
    New-Item -ItemType Directory -Path $outputRoot -Force | Out-Null
    $apk = Join-Path $outputRoot "蓝色大肥鱼-$version.apk"
    Copy-Item -LiteralPath (Join-Path $projectRoot 'app\build\outputs\apk\release\app-release.apk') -Destination $apk
    $digest = (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant()
    [IO.File]::WriteAllText((Join-Path $outputRoot 'SHA256SUMS.txt'), "$digest  蓝色大肥鱼-$version.apk`n", [Text.UTF8Encoding]::new($false))
    Write-Output "Signed APK: $apk"
} finally { Pop-Location; $signingPassword = $null; $properties = $null; $certificate = $null }

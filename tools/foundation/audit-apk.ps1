param(
    [Parameter(Mandatory)][string]$Apk,
    [Parameter(Mandatory)][string]$SdkRoot,
    [Parameter(Mandatory)][string]$OutputDirectory
)
$ErrorActionPreference = 'Stop'
New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
$aapt = Join-Path $SdkRoot 'build-tools\36.0.0\aapt2.exe'
if (!(Test-Path $aapt)) { throw 'Android Build Tools 36.0.0 are required by this AGP baseline.' }
$badging = & $aapt dump badging $Apk
if ($LASTEXITCODE -ne 0) { throw 'APK badging failed' }
$manifest = & $aapt dump xmltree --file AndroidManifest.xml $Apk
if ($LASTEXITCODE -ne 0) { throw 'Manifest inspection failed' }
if (($badging | Out-String) -notmatch "package: name='com.drivedeck.player.test'") { throw 'Unexpected package identity' }
if (($badging | Out-String) -notmatch "application-label:'DriveDeck Player Test'") { throw 'Missing Test label' }
if (($manifest | Out-String) -notmatch 'android:allowBackup.*0x0') { throw 'Backup is not explicitly disabled' }
$element = ''
$allowedCarKeys = @('com.google.android.gms.car.application', 'com.google.android.gms.car.notification.SmallIcon')
$observedCarKeys = @()
foreach ($line in $manifest) {
    if ($line -match '^\s*E: ([^ ]+)') { $element = $Matches[1] }
    if ($line -match 'com.google.android.gms|com.google.firebase|crashlytics') {
        $name = [regex]::Match($line, 'android:name(?:\([^)]*\))?="([^"]+)"').Groups[1].Value
        if ($element -eq 'meta-data' -and $allowedCarKeys -contains $name) { $observedCarKeys += $name }
        else { throw 'Forbidden runtime component or unreviewed service metadata' }
    }
}
$badging | Set-Content (Join-Path $OutputDirectory 'apk-badging.txt') -Encoding utf8
$manifest | Set-Content (Join-Path $OutputDirectory 'apk-manifest.txt') -Encoding utf8
$apkItem = Get-Item -LiteralPath $Apk
[ordered]@{ file=$apkItem.Name; bytes=$apkItem.Length; sha256=(Get-FileHash -LiteralPath $Apk -Algorithm SHA256).Hash.ToLower(); package='com.drivedeck.player.test'; label='DriveDeck Player Test'; allowBackup=$false; allowedAndroidAutoMetadata=$observedCarKeys } | ConvertTo-Json | Tee-Object -FilePath (Join-Path $OutputDirectory 'apk-summary.json')

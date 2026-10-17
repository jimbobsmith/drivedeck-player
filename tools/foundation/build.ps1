param(
    [Parameter(Mandatory)][string]$JdkHome,
    [Parameter(Mandatory)][string]$SdkRoot,
    [string]$GradleHome,
    [string]$GradleUserHome = 'D:\tmp\dd\gradle-home'
)
$ErrorActionPreference = 'Stop'
$playerRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$oldJava = $env:JAVA_HOME; $oldSdk = $env:ANDROID_HOME; $oldGradle = $env:GRADLE_USER_HOME
try {
    $env:JAVA_HOME = $JdkHome; $env:ANDROID_HOME = $SdkRoot; $env:GRADLE_USER_HOME = $GradleUserHome
    $javaVersion = & (Join-Path $JdkHome 'bin\java.exe') -version 2>&1 | Out-String
    if ($javaVersion -notmatch 'version "25\.') { throw 'Use JDK 25, matching the pinned upstream source.' }
    if (!(Test-Path (Join-Path $SdkRoot 'platforms\android-37.0\android.jar'))) { throw 'Install platforms;android-37.0 in SdkRoot.' }
    if ($GradleHome) { $gradle = Join-Path $GradleHome 'bin\gradle.bat' } else { $gradle = Join-Path $playerRoot 'gradlew.bat' }
    Push-Location $playerRoot
    try {
        & $gradle :app:assembleDebug :app:testDebugUnitTest :desktop-spike:installDist :app:writeFoundationRuntimeAudit --no-configuration-cache --no-daemon --max-workers=2 --console=plain
        if ($LASTEXITCODE -ne 0) { throw "Foundation build failed: $LASTEXITCODE" }
    } finally { Pop-Location }
} finally {
    $env:JAVA_HOME = $oldJava; $env:ANDROID_HOME = $oldSdk; $env:GRADLE_USER_HOME = $oldGradle
}

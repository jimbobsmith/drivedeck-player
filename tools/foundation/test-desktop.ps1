param(
    [Parameter(Mandatory, ParameterSetName='YouTube')][ValidatePattern('^[A-Za-z0-9_-]{11}$')][string]$VideoId,
    [Parameter(ParameterSetName='YouTube')][switch]$ExperimentalPairedTracks,
    [Parameter(Mandatory, ParameterSetName='Fixture')][string]$Fixture,
    [Parameter(ParameterSetName='Fixture')][string]$AudioFixture,
    [switch]$Present,
    [ValidateRange(20,300)][int]$ObserveSeconds = 20,
    [ValidateSet('baseline','hd720','hd1080')][string]$Profile = 'baseline',
    [ValidateRange(1,600)][int]$TimeoutSeconds = ($ObserveSeconds + 80)
)
$ErrorActionPreference = 'Stop'
$playerRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$launcher = Join-Path $playerRoot 'desktop-spike\build\install\drivedeck-player-desktop-spike\bin\drivedeck-player-desktop-spike.bat'
if (!(Test-Path $launcher)) { throw 'Build/install the desktop-spike distribution first; see docs/foundation/README.md.' }
$scratch = 'D:\tmp\dd\player-desktop-spike'
New-Item -ItemType Directory -Path $scratch -Force | Out-Null
$previousTimeout = $env:DD_PLAYER_PROBE_TIMEOUT_SECONDS
$previousObserve = $env:DD_PLAYER_OBSERVE_SECONDS
$previousProfile = $env:DD_PLAYER_PROFILE
$env:DD_PLAYER_PROBE_TIMEOUT_SECONDS = [string]$TimeoutSeconds
$env:DD_PLAYER_OBSERVE_SECONDS = [string]$ObserveSeconds
$env:DD_PLAYER_PROFILE = $Profile
$nativeLog = Join-Path $scratch ('native-' + [guid]::NewGuid().ToString('N') + '.log')
try {
    $probeArgs = @()
    if ($Present) { $probeArgs += '--present' }
    if ($PSCmdlet.ParameterSetName -eq 'YouTube') {
        $mode = if ($ExperimentalPairedTracks) { '--youtube-paired' } else { '--youtube' }
        $probeArgs += @($mode, $VideoId)
    } elseif ($AudioFixture) {
        $probeArgs += @('--fixture-paired', (Resolve-Path -LiteralPath $Fixture).Path, (Resolve-Path -LiteralPath $AudioFixture).Path)
    } else {
        $probeArgs += @('--fixture', (Resolve-Path -LiteralPath $Fixture).Path)
    }
    $resultText = & $launcher @probeArgs 2> $nativeLog
    $probeExit = $LASTEXITCODE
} finally {
    $env:DD_PLAYER_PROBE_TIMEOUT_SECONDS = $previousTimeout
    $env:DD_PLAYER_OBSERVE_SECONDS = $previousObserve
    $env:DD_PLAYER_PROFILE = $previousProfile
}
if ($resultText) { $resultText }
else {
    [ordered]@{schemaVersion=2; result='failed'; stage='native_process'; errorType='NativeProcessFailure'; processExitCode=$probeExit} | ConvertTo-Json
}
if ($probeExit -ne 0) { throw "Desktop feasibility test failed: $probeExit. Launcher diagnostics: $nativeLog. Worker logs: $scratch\native-*.log" }

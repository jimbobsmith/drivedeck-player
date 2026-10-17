param(
    [Parameter(Mandatory, ParameterSetName='YouTube')][ValidatePattern('^[A-Za-z0-9_-]{11}$')][string]$VideoId,
    [Parameter(ParameterSetName='YouTube')][switch]$ExperimentalPairedTracks,
    [Parameter(Mandatory, ParameterSetName='Fixture')][string]$Fixture,
    [ValidateRange(1,100)][int]$TimeoutSeconds = 100
)
$ErrorActionPreference = 'Stop'
$playerRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$launcher = Join-Path $playerRoot 'desktop-spike\build\install\drivedeck-player-desktop-spike\bin\drivedeck-player-desktop-spike.bat'
if (!(Test-Path $launcher)) { throw 'Build/install the desktop-spike distribution first; see docs/foundation/README.md.' }
$scratch = 'D:\tmp\dd\player-desktop-spike'
New-Item -ItemType Directory -Path $scratch -Force | Out-Null
$previousTimeout = $env:DD_PLAYER_PROBE_TIMEOUT_SECONDS
$env:DD_PLAYER_PROBE_TIMEOUT_SECONDS = [string]$TimeoutSeconds
$nativeLog = Join-Path $scratch ('native-' + [guid]::NewGuid().ToString('N') + '.log')
try {
    if ($PSCmdlet.ParameterSetName -eq 'YouTube') {
        $mode = if ($ExperimentalPairedTracks) { '--youtube-paired' } else { '--youtube' }
        $resultText = & $launcher $mode $VideoId 2> $nativeLog
    } else {
        $resultText = & $launcher --fixture (Resolve-Path -LiteralPath $Fixture).Path 2> $nativeLog
    }
    $probeExit = $LASTEXITCODE
} finally { $env:DD_PLAYER_PROBE_TIMEOUT_SECONDS = $previousTimeout }
if ($resultText) { $resultText }
else {
    [ordered]@{schemaVersion=2; result='failed'; stage='native_process'; errorType='NativeProcessFailure'; processExitCode=$probeExit} | ConvertTo-Json
}
if ($probeExit -ne 0) { throw "Desktop feasibility test failed: $probeExit. Launcher diagnostics: $nativeLog. Worker logs: $scratch\native-*.log" }

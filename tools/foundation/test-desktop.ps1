param(
    [Parameter(Mandatory, ParameterSetName='YouTube')][ValidatePattern('^[A-Za-z0-9_-]{11}$')][string]$VideoId,
    [Parameter(ParameterSetName='YouTube')][switch]$ExperimentalPairedTracks,
    [Parameter(Mandatory, ParameterSetName='Fixture')][string]$Fixture
)
$ErrorActionPreference = 'Stop'
$playerRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$launcher = Join-Path $playerRoot 'desktop-spike\build\install\drivedeck-player-desktop-spike\bin\drivedeck-player-desktop-spike.bat'
if (!(Test-Path $launcher)) { throw 'Build/install the desktop-spike distribution first; see docs/foundation/README.md.' }
$scratch = 'D:\tmp\dd\player-desktop-spike'
New-Item -ItemType Directory -Path $scratch -Force | Out-Null
$nativeLog = Join-Path $scratch ('native-' + [guid]::NewGuid().ToString('N') + '.log')
if ($PSCmdlet.ParameterSetName -eq 'YouTube') {
    $mode = if ($ExperimentalPairedTracks) { '--youtube-paired' } else { '--youtube' }
    $resultText = & $launcher $mode $VideoId 2> $nativeLog
} else {
    $resultText = & $launcher --fixture (Resolve-Path -LiteralPath $Fixture).Path 2> $nativeLog
}
$probeExit = $LASTEXITCODE
if ($resultText) { $resultText }
else {
    [ordered]@{schemaVersion=1; result='failed'; stage='native_process'; errorType='NativeProcessFailure'; processExitCode=$probeExit} | ConvertTo-Json
}
if ($probeExit -ne 0) { throw "Desktop feasibility test failed: $probeExit. Native diagnostics: $nativeLog" }

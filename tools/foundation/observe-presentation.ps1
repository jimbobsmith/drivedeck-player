param([Parameter(Mandatory)][string]$Python,[Parameter(Mandatory)][string]$JavaHome,[Parameter(Mandatory)][string]$Distribution,[Parameter(Mandatory)][string]$OutputDirectory,[ValidatePattern('^[A-Za-z0-9_-]{11}$')][string]$VideoId='aqz-KE-bpKQ',[ValidateRange(100,300)][int]$ObserveSeconds=300,[ValidateSet('hd720','hd1080')][string]$Profile='hd1080',[switch]$KeepDisplayAwake,[switch]$PilotContinuity,[string]$FixtureDirectory,[ValidateSet('relay','hold','disconnect','permanent','cancel')][string]$NetworkFault='disconnect')
# Windows PowerShell -STA. Passive unless explicitly requested; no focus, input, lock or persistent power changes.
$ErrorActionPreference='Stop'
$OutputDirectory=[IO.Path]::GetFullPath($OutputDirectory)
if(!$OutputDirectory.StartsWith('D:\tmp\dd\',[StringComparison]::OrdinalIgnoreCase)){throw 'Output directory must be inside D:/tmp/dd'}
if(!(Test-Path -LiteralPath $Python -PathType Leaf)){throw 'Python executable not found'}
if(!(Test-Path -LiteralPath (Join-Path $JavaHome 'bin\java.exe') -PathType Leaf)){throw 'Java executable not found'}
if(!(Test-Path -LiteralPath (Join-Path $Distribution 'lib\DriveDeckPlayerDesktopSpike.jar') -PathType Leaf)){throw 'Desktop distribution not found'}
if(Test-Path -LiteralPath $OutputDirectory){throw 'Fresh output directory required'}
$previousTemp=$env:TEMP;$previousTmp=$env:TMP
try {
 $env:TEMP='D:\tmp\dd\window-observer-compiler';$env:TMP=$env:TEMP
 [IO.Directory]::CreateDirectory($env:TEMP) | Out-Null
Add-Type -AssemblyName System.Windows.Forms
Add-Type -ReferencedAssemblies System.Windows.Forms -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
using System.Windows.Forms;
public sealed class PresentationObserver : NativeWindow,IDisposable {
 [StructLayout(LayoutKind.Sequential)] struct Rect {public int left,top,right,bottom;}
 [StructLayout(LayoutKind.Sequential)] struct LastInput {public uint size,time;}
 [DllImport("user32.dll",CharSet=CharSet.Unicode)] static extern IntPtr FindWindow(string c,string title);
 [DllImport("user32.dll")] static extern bool IsWindowVisible(IntPtr w);
 [DllImport("user32.dll")] static extern bool IsIconic(IntPtr w);
 [DllImport("user32.dll")] static extern IntPtr GetForegroundWindow();
 [DllImport("user32.dll")] static extern bool GetWindowRect(IntPtr w,out Rect r);
 [DllImport("user32.dll")] static extern bool GetLastInputInfo(ref LastInput info);
 [DllImport("dwmapi.dll")] static extern int DwmGetWindowAttribute(IntPtr w,int a,out int v,int n);
 [DllImport("user32.dll",SetLastError=true)] static extern IntPtr RegisterPowerSettingNotification(IntPtr w,ref Guid g,int flags);
 [DllImport("user32.dll")] static extern bool UnregisterPowerSettingNotification(IntPtr h);
 [DllImport("kernel32.dll",SetLastError=true)] public static extern uint SetThreadExecutionState(uint flags);
 IntPtr registration; public int DisplayState=-1;
 public PresentationObserver() {
  CreateHandle(new CreateParams {Caption="DD validation power observer",Parent=new IntPtr(-3)});
  var guid=new Guid("2B84C20E-AD23-4DDF-93DB-05FFBD7EFCA5");
  registration=RegisterPowerSettingNotification(Handle,ref guid,0);
  if(registration==IntPtr.Zero){DestroyHandle();throw new InvalidOperationException("PowerNotificationRegistrationFailed");}
 }
 protected override void WndProc(ref Message message) {
  if(message.Msg==0x218 && message.WParam.ToInt64()==0x8013 && Marshal.ReadInt32(message.LParam,16)>=4)DisplayState=Marshal.ReadInt32(message.LParam,20);
  base.WndProc(ref message);
 }
 public string Snapshot(long elapsed) {
  var input=new LastInput();input.size=(uint)Marshal.SizeOf(input);
  long idle=GetLastInputInfo(ref input)?(long)unchecked((uint)Environment.TickCount-input.time):-1;
  var w=FindWindow(null,"DriveDeck Player \u2014 Playback validation");
  int found=w!=IntPtr.Zero?1:0,visible=-1,minimized=-1,cloaked=-1,foreground=-1,coverage=-1;
  if(found==1){
   visible=IsWindowVisible(w)?1:0;minimized=IsIconic(w)?1:0;
   if(DwmGetWindowAttribute(w,14,out cloaked,4)!=0)cloaked=-1;
   var top=GetForegroundWindow();foreground=top==w?1:0;Rect own,other;
   if(GetWindowRect(w,out own) && GetWindowRect(top,out other)){
    long area=(long)Math.Max(0,own.right-own.left)*Math.Max(0,own.bottom-own.top);
    long overlap=(long)Math.Max(0,Math.Min(own.right,other.right)-Math.Max(own.left,other.left))*Math.Max(0,Math.Min(own.bottom,other.bottom)-Math.Max(own.top,other.top));
    coverage=area>0 && foreground==0?(int)(100*overlap/area):0;
   }
  }
  return DateTimeOffset.UtcNow.ToUnixTimeMilliseconds()+","+elapsed+","+found+","+visible+","+minimized+","+cloaked+","+foreground+","+coverage+","+DisplayState+","+idle;
 }
 public void Dispose(){if(registration!=IntPtr.Zero)UnregisterPowerSettingNotification(registration);DestroyHandle();}
}
'@
}finally{$env:TEMP=$previousTemp;$env:TMP=$previousTmp}
function NativeArgument([string]$Value) {
 if($Value.Contains('"')){throw 'Invalid argument quote'}
 return '"'+($Value -replace '(\\+)$','$1$1')+'"'
}
$root=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
# A fixture defaults to an uninterrupted relay control; pass NetworkFault explicitly for a fixture outage.
if($FixtureDirectory -and !$PSBoundParameters.ContainsKey('NetworkFault')){$NetworkFault='relay'}
$arguments=@((Join-Path $PSScriptRoot 'validate-presentation.py'),'--java-home',$JavaHome,'--distribution',$Distribution,'--output-directory',$OutputDirectory,'--profile',$Profile,'--observe-seconds',[string]$ObserveSeconds,'--network-fault',$NetworkFault)
if($PilotContinuity){$arguments+='--pilot-continuity'}
if($FixtureDirectory){$arguments+=@('--fixture-directory',$FixtureDirectory)}else{$arguments+=@('--video-id',$VideoId)}
$observer=New-Object PresentationObserver
$previousExecution=[uint32]0;$started=$false;$process=New-Object System.Diagnostics.Process
try{
 if($KeepDisplayAwake){$previousExecution=[PresentationObserver]::SetThreadExecutionState([uint32]2147483651);if($previousExecution -eq 0){throw 'DisplayAwakeRequestFailed'}}
 [IO.Directory]::CreateDirectory($OutputDirectory) | Out-Null
 $file=Join-Path $OutputDirectory 'window-state.csv'
 [IO.File]::WriteAllText($file,"epochMs,elapsedMs,windowFound,windowVisible,windowMinimized,windowCloaked,foregroundOwned,foregroundCoveragePercent,displayState,idleMs"+[Environment]::NewLine)
 $process.StartInfo.FileName=$Python;$process.StartInfo.Arguments=($arguments | ForEach-Object {NativeArgument $_}) -join ' '
 $process.StartInfo.WorkingDirectory=$root;$process.StartInfo.UseShellExecute=$false;$process.StartInfo.CreateNoWindow=$true
 $process.StartInfo.WindowStyle=[Diagnostics.ProcessWindowStyle]::Hidden
 $process.StartInfo.RedirectStandardOutput=$true;$process.StartInfo.RedirectStandardError=$true
 $started=$process.Start();if(!$started){throw 'ProbeStartFailed'}
 $stdout=$process.StandardOutput.ReadToEndAsync();$stderr=$process.StandardError.ReadToEndAsync()
 $timer=[Diagnostics.Stopwatch]::StartNew();$nextSample=0
 while(!$process.HasExited){
  [Windows.Forms.Application]::DoEvents()
  if($timer.ElapsedMilliseconds -ge $nextSample){[IO.File]::AppendAllText($file,$observer.Snapshot($timer.ElapsedMilliseconds)+[Environment]::NewLine);$nextSample+=1000}
  if($timer.Elapsed.TotalSeconds -gt $ObserveSeconds+120){throw 'ObserverDeadline'}
  Start-Sleep -Milliseconds 50
 }
 [IO.File]::WriteAllText((Join-Path $OutputDirectory 'observer-stdout.json'),$stdout.Result)
 [IO.File]::WriteAllText((Join-Path $OutputDirectory 'observer-stderr.log'),$stderr.Result)
 [ordered]@{schemaVersion=1;result='completed';harnessExit=$process.ExitCode;keepDisplayAwake=[bool]$KeepDisplayAwake;networkFault=$NetworkFault;windowSamples=[Math]::Ceiling($nextSample/1000)} | ConvertTo-Json | Set-Content (Join-Path $OutputDirectory 'window-observer.json') -Encoding UTF8
}finally{
 if($started -and !$process.HasExited){& taskkill.exe /PID $process.Id /T /F > $null 2>&1}
 if($previousExecution -ne 0){[void][PresentationObserver]::SetThreadExecutionState($previousExecution)}
 $observer.Dispose();$process.Dispose()
}

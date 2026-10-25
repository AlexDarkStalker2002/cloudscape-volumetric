param([switch]$ValidateOnly)
$ErrorActionPreference='Stop'
$backup=[IO.Path]::GetFullPath($PSScriptRoot)
$m=Get-Content -LiteralPath (Join-Path $backup 'manifest.json') -Raw|ConvertFrom-Json
$profile=[IO.Path]::GetFullPath($m.profile)
function Hash([string]$p){(Get-FileHash -LiteralPath $p -Algorithm SHA256).Hash}
Add-Type -AssemblyName System.IO.Compression.FileSystem
function ActiveCloudscape([string]$root){
 foreach($file in Get-ChildItem -LiteralPath $root -File -Filter '*.jar'){
  $archive=[IO.Compression.ZipFile]::OpenRead($file.FullName)
  try{
   $entry=$archive.GetEntry('fabric.mod.json');if(-not $entry){continue}
   $reader=[IO.StreamReader]::new($entry.Open());try{$meta=$reader.ReadToEnd()|ConvertFrom-Json}finally{$reader.Dispose()}
   if($meta.id -eq 'cloudscape_volumetric'){$file}
  }finally{$archive.Dispose()}
 }
}
function Safe([string]$root,[string]$relative){
 $p=[IO.Path]::GetFullPath((Join-Path $root $relative))
 if(-not $p.StartsWith($root+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Invalid manifest path'};$p
}
if($m.oldJarName){$old=Safe $backup ('mods/'+$m.oldJarName);if((Hash $old) -ne $m.oldJarSHA256){throw 'Old JAR backup hash mismatch'}}
$savedConfig=Safe $backup 'config/cloudscape-volumetric.json'
if($m.hadConfig -and (Hash $savedConfig) -ne $m.oldConfigSHA256){throw 'Config backup hash mismatch'}
if($ValidateOnly){@{backupVerified=$true;profile=$profile;filesModified=$false}|ConvertTo-Json;exit 0}
if(@(Get-CimInstance Win32_Process|Where-Object {$_.Name -match '^java(w)?\.exe$' -and $_.CommandLine -match 'net\.fabricmc\.loader|KnotClient|net\.minecraft\.client\.main\.Main|validation\.args'}).Count){throw 'Close Minecraft first'}
$mods=Safe $profile 'mods';$config=Safe $profile 'config/cloudscape-volumetric.json'
$active=@(ActiveCloudscape $mods)
if($active.Count -eq 1 -and $m.oldJarName -and $active[0].Name -eq $m.oldJarName -and (Hash $active[0].FullName) -eq $m.oldJarSHA256){Write-Output 'Original JAR is already active; no files changed';exit 0}
if($active.Count -ne 1 -or $active[0].Name -ne $m.candidateJarName -or (Hash $active[0].FullName) -ne $m.candidateJarSHA256){throw 'Installed version changed; no files changed'}
$undo=Safe $backup ('undo-'+(Get-Date -Format 'yyyyMMdd-HHmmss-fff'));New-Item -ItemType Directory -Path $undo|Out-Null
$hadCurrent=Test-Path -LiteralPath $config
if($hadCurrent){Copy-Item -LiteralPath $config -Destination (Safe $undo 'cloudscape-volumetric.json')}
$retired=Safe $undo $m.candidateJarName
$oldTarget=if($m.oldJarName){Safe $profile ('mods/'+$m.oldJarName)}else{$null}
try{
 Move-Item -LiteralPath $active[0].FullName -Destination $retired
 if($oldTarget){Copy-Item -LiteralPath $old -Destination $oldTarget}
 if($m.hadConfig){Copy-Item -LiteralPath $savedConfig -Destination $config -Force}
 elseif(Test-Path -LiteralPath $config){Move-Item -LiteralPath $config -Destination (Safe $undo 'created-config.json')}
 if($oldTarget -and (Hash $oldTarget) -ne $m.oldJarSHA256){throw 'Restored JAR hash mismatch'}
 if($m.hadConfig -and (Hash $config) -ne $m.oldConfigSHA256){throw 'Restored config hash mismatch'}
}catch{
 if($oldTarget -and (Test-Path -LiteralPath $oldTarget)){Move-Item -LiteralPath $oldTarget -Destination (Safe $undo 'failed-old.jar')}
 if(Test-Path -LiteralPath $retired){Move-Item -LiteralPath $retired -Destination (Safe $profile ('mods/'+$m.candidateJarName))}
 if($hadCurrent){Copy-Item -LiteralPath (Safe $undo 'cloudscape-volumetric.json') -Destination $config -Force}
 throw
}
Write-Output "Restored previous JAR/config; newer files saved in $undo. Weather checkpoints retained."

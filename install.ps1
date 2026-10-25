param(
 [Parameter(Mandatory=$true)][string]$Profile,
 [Parameter(Mandatory=$true)][string]$Jar,
 [Parameter(Mandatory=$true)][string]$ExpectedSHA256,
 [Parameter(Mandatory=$true)][string]$BackupRoot
)
$ErrorActionPreference='Stop'
function Hash([string]$p){(Get-FileHash -LiteralPath $p -Algorithm SHA256).Hash}
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
function Closed {
 if(@(Get-CimInstance Win32_Process|Where-Object {$_.Name -match '^java(w)?\.exe$' -and $_.CommandLine -match 'net\.fabricmc\.loader|KnotClient|net\.minecraft\.client\.main\.Main|validation\.args'}).Count){throw 'Close Minecraft and validation clients first'}
}
Closed
$profilePath=[IO.Path]::GetFullPath($Profile);$jarPath=[IO.Path]::GetFullPath($Jar)
if((Hash $jarPath) -ne $ExpectedSHA256){throw 'Candidate JAR hash mismatch'}
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip=[IO.Compression.ZipFile]::OpenRead($jarPath)
try{
 $reader=[IO.StreamReader]::new($zip.GetEntry('fabric.mod.json').Open());try{$metadata=$reader.ReadToEnd()|ConvertFrom-Json}finally{$reader.Dispose()}
 if($metadata.id -ne 'cloudscape_volumetric'){throw 'Unexpected mod ID'}
}finally{$zip.Dispose()}
$jarName="cloudscape-volumetric-$($metadata.version)+mc26.3.jar"
$mods=Join-Path $profilePath 'mods';if(-not(Test-Path -LiteralPath $mods -PathType Container)){throw 'Profile mods directory missing'}
$active=@(ActiveCloudscape $mods)
if($active.Count -gt 1){throw 'Resolve duplicate Cloudscape JARs before installing'}
if($active.Count -eq 1 -and (Hash $active[0].FullName) -eq $ExpectedSHA256){Write-Output 'Candidate is already installed';exit 0}
$backupBase=[IO.Path]::GetFullPath($BackupRoot)
if($backupBase.Equals($profilePath,[StringComparison]::OrdinalIgnoreCase) -or $backupBase.StartsWith($profilePath+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'BackupRoot must be outside the active profile'}
$backup=Join-Path $backupBase ('cloudscape-'+(Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
New-Item -ItemType Directory -Path (Join-Path $backup 'mods'),(Join-Path $backup 'config') -Force|Out-Null
$config=Join-Path $profilePath 'config/cloudscape-volumetric.json'
$hadConfig=Test-Path -LiteralPath $config
if($hadConfig){Copy-Item -LiteralPath $config -Destination (Join-Path $backup 'config/cloudscape-volumetric.json')}
$manifest=[ordered]@{profile=$profilePath;oldJarName=$null;oldJarSHA256=$null;hadConfig=$hadConfig;oldConfigSHA256=$null;candidateJarName=$jarName;candidateJarSHA256=$ExpectedSHA256;installed=$false}
if($active.Count){$manifest.oldJarName=$active[0].Name;$manifest.oldJarSHA256=Hash $active[0].FullName;Copy-Item -LiteralPath $active[0].FullName -Destination (Join-Path $backup ('mods/'+$active[0].Name))}
if($hadConfig){$manifest.oldConfigSHA256=Hash $config}
$weather=Join-Path $profilePath 'config/cloudscape-weather'
if(Test-Path -LiteralPath $weather){Copy-Item -LiteralPath $weather -Destination (Join-Path $backup 'config/cloudscape-weather') -Recurse}
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'restore.ps1') -Destination (Join-Path $backup 'Restore.ps1')
$manifestPath=Join-Path $backup 'manifest.json'
$manifest|ConvertTo-Json|Set-Content -LiteralPath $manifestPath -Encoding utf8
& (Join-Path $backup 'Restore.ps1') -ValidateOnly|Out-Null
if($LASTEXITCODE -ne 0){throw 'Backup validation failed'}
$target=Join-Path $mods $jarName
$staging=Join-Path $mods ($jarName+'.pending')
if(Test-Path -LiteralPath $target){throw 'Candidate target exists with different bytes'}
if(Test-Path -LiteralPath $staging){throw 'Staging path already exists'}
Copy-Item -LiteralPath $jarPath -Destination $staging
if((Hash $staging) -ne $ExpectedSHA256){throw 'Staging hash mismatch'}
$installedCandidate=$false
try{
 Closed
 if($hadConfig -and (Hash $config) -ne $manifest.oldConfigSHA256){throw 'Configuration changed during installation'}
 if($active.Count){
  if((Hash $active[0].FullName) -ne $manifest.oldJarSHA256){throw 'Installed JAR changed during installation'}
  Move-Item -LiteralPath $active[0].FullName -Destination (Join-Path $backup 'retired-active.jar')
 }
 Move-Item -LiteralPath $staging -Destination $target
 $installedCandidate=$true
 if((Hash $target) -ne $ExpectedSHA256){throw 'Installed hash mismatch'}
 $manifest.installed=$true;$manifest|ConvertTo-Json|Set-Content -LiteralPath $manifestPath -Encoding utf8
}catch{
 if($installedCandidate -and (Test-Path -LiteralPath $target)){Move-Item -LiteralPath $target -Destination (Join-Path $backup 'failed-candidate.jar')}
 if(Test-Path -LiteralPath $staging){Move-Item -LiteralPath $staging -Destination (Join-Path $backup 'failed-staging.jar')}
 $retired=Join-Path $backup 'retired-active.jar'
 if(Test-Path -LiteralPath $retired){Move-Item -LiteralPath $retired -Destination (Join-Path $mods $manifest.oldJarName)}
 throw
}
@{installed=$target;sha256=$ExpectedSHA256;backup=$backup;configurationModified=$false}|ConvertTo-Json

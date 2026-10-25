param(
 [Parameter(Mandatory=$true)][string]$MinecraftHome,
 [Parameter(Mandatory=$true)][string]$Profile,
 [Parameter(Mandatory=$true)][string]$VersionJson,
 [Parameter(Mandatory=$true)][string]$JavaHome,
 [Parameter(Mandatory=$true)][string]$Jar,
 [Parameter(Mandatory=$true)][string]$RunRoot,
 [ValidateSet('Clean','UI','Smoke','Unbound','Unsupported','Motion','Soak','FPS')][string]$Case='Clean',
 [string]$JavaExecutable
)
$ErrorActionPreference='Stop'
if(@(Get-CimInstance Win32_Process|Where-Object {$_.Name -match '^java(w)?\.exe$' -and $_.CommandLine -match 'net.fabricmc.loader|validation\.args|ComputeBench|ResidentParity'}).Count){throw 'Another Minecraft/GPU run is active'}
$stand=[IO.Path]::GetFullPath($RunRoot);$profilePath=[IO.Path]::GetFullPath($Profile)
if($stand.StartsWith($profilePath+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'RunRoot must be outside the user profile'}
if(Test-Path -LiteralPath $stand){throw 'Use a new isolated RunRoot; previous evidence is preserved'}
$snapshot=Join-Path $stand 'evidence/accepted-profile'
New-Item -ItemType Directory -Path (Join-Path $snapshot 'config'),(Join-Path $snapshot 'shaderpacks'),(Join-Path $stand 'game-stand'),(Join-Path $stand 'tmp') -Force|Out-Null
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'validation/game') -Destination (Join-Path $stand 'game-stand/validation') -Recurse
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'validation/launcher.ps1') -Destination (Join-Path $stand 'game-stand/validate-game.ps1')
$relativeFiles=@('options.txt','config/cloudscape-volumetric.json','config/iris.properties','config/DistantHorizons.toml','config/lithium.properties','config/sodium-mixins.properties','config/modmenu.json','config/yacl.json5','shaderpacks/ComplementaryReimagined_r5.9.3.zip.txt','shaderpacks/ComplementaryUnbound_r5.9.3.zip.txt')
foreach($relative in $relativeFiles){$source=Join-Path $Profile $relative;if(Test-Path -LiteralPath $source){Copy-Item -LiteralPath $source -Destination (Join-Path $snapshot $relative)}}
if(-not(Test-Path -LiteralPath (Join-Path $snapshot 'options.txt'))){Set-Content -LiteralPath (Join-Path $snapshot 'options.txt') -Value 'lang:en_us' -Encoding utf8}
$lock=Get-Content -LiteralPath (Join-Path $PSScriptRoot 'dependencies.lock.json') -Raw|ConvertFrom-Json
$classpath=@(foreach($dependency in $lock.dependencies){
 $base=if($dependency.base -eq 'minecraft'){$MinecraftHome}else{$Profile};$file=Join-Path $base $dependency.path
 if((Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash -ne $dependency.sha256){throw "Dependency changed: $($dependency.path)"};[IO.Path]::GetFullPath($file)
})
$classpath|ConvertTo-Json|Set-Content -LiteralPath (Join-Path $stand 'evidence/cloudscape-classpath.json') -Encoding utf8
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip=[IO.Compression.ZipFile]::OpenRead([IO.Path]::GetFullPath($Jar))
try{$reader=[IO.StreamReader]::new($zip.GetEntry('fabric.mod.json').Open());try{$metadata=$reader.ReadToEnd()|ConvertFrom-Json}finally{$reader.Dispose()}}finally{$zip.Dispose()}
$config=Join-Path $stand 'reference-look.json';Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'presets/reference-look.json') -Destination $config
$extra=@('-Dcloudscape.validation.weatherSeed=0.317','-Dcloudscape.validation.liveTime=true','-Dcloudscape.validation.expectedViewSteps=192','-Dcloudscape.validation.expectedReflectionSteps=64','-Dcloudscape.validation.expectedLightSteps=8')
$params=@{Profile=$Profile;MinecraftHome=$MinecraftHome;JavaHome=$JavaHome;VersionJson=$VersionJson;CandidateJar=$Jar;ModVersion=$metadata.version;RunLabel='acceptance';CloudConfigFile=$config;RawCloudConfig=$true;Shader='Reimagined';UseProfileShaderOptions=$true;FullProfile=$false;Fullscreen=$true;FullResolutionClouds=$true;DefaultRuntimeOptions=$true;RenderDistance=16;SimulationDistance=6;MaxFps=180;Width=2560;Height=1440}
if($JavaExecutable){$params.JavaExecutable=$JavaExecutable}
switch($Case){
 'Clean' {$params.RawCloudConfig=$false;$params.MissingCloudConfig=$true;$params.UseProfileShaderOptions=$false;$extra=@('-Dcloudscape.validation.ui=true','-Dcloudscape.validation.uiOnly=true','-Dcloudscape.validation.cleanInstallation=true','-Dcloudscape.validation.expectedConfigNotice=config.missing')}
 'UI' {$extra+=@('-Dcloudscape.validation.ui=true','-Dcloudscape.validation.uiOnly=true');$cfg=Get-Content -LiteralPath $config -Raw|ConvertFrom-Json;$cfg.shapeMode=0;$cfg|ConvertTo-Json|Set-Content -LiteralPath $config -Encoding utf8}
 'Soak' {$extra+=@('-Dcloudscape.validation.releaseSoak=true','-Dcloudscape.validation.soakMinutes=60')}
 'Unbound' {$params.Shader='Unbound';$params.Quick=$true;$extra+=@('-Dcloudscape.validation.noAtlas=true','-Dcloudscape.validation.dhWarmupTicks=200')}
 'Unsupported' {$params.UnsupportedFixture=$true;$params.Quick=$true;$extra+=@('-Dcloudscape.validation.unsupportedFixture=true','-Dcloudscape.validation.dhWarmupTicks=200')}
 'Motion' {$params.Motion=$true;$params.OpaqueEdge=$true;$params.Landscape=$true}
 'FPS' {$params.Performance=$true;$params.Landscape=$true;$extra+=@('-Dcloudscape.validation.sharpReview=true','-Dcloudscape.validation.releaseFps=true','-Dcloudscape.validation.dhWarmupTicks=1200');$cfg=Get-Content -LiteralPath $config -Raw|ConvertFrom-Json;$cfg.weatherVariability=$false;$cfg|ConvertTo-Json|Set-Content -LiteralPath $config -Encoding utf8}
}
$env:TEMP=Join-Path $stand 'tmp';$env:TMP=$env:TEMP
$extra+=@("-Djava.io.tmpdir=$env:TEMP");$params.AdditionalJvmArguments=$extra
& (Join-Path $stand 'game-stand/validate-game.ps1') @params

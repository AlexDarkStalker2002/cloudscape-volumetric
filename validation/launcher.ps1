param([Parameter(Mandatory=$true)][string]$Profile,[Parameter(Mandatory=$true)][string]$MinecraftHome,[Parameter(Mandatory=$true)][string]$JavaHome,[Parameter(Mandatory=$true)][string]$VersionJson,[switch]$UnsupportedFixture,[string]$FixtureWorld,[switch]$RawCloudConfig,[switch]$MissingCloudConfig,[Parameter(Mandatory=$true)][string]$CandidateJar,[Parameter(Mandatory=$true)][ValidatePattern('^[A-Za-z0-9_-]+$')][string]$RunLabel,[switch]$Fullscreen, [switch]$Baseline, [switch]$FullProfile, [switch]$BetterClouds, [switch]$CubicPlus, [switch]$Quick, [switch]$Performance, [Parameter(Mandatory=$true)][string]$ModVersion, [string]$CloudConfigFile, [int]$RenderDistance=12, [ValidateRange(2,32)][int]$SimulationDistance=5, [int]$MaxFps=120, [switch]$OpaqueEdge, [switch]$Landscape, [switch]$Motion, [switch]$UseProfileShaderOptions, [string[]]$ShaderOptionOverrides=@(), [switch]$FullResolutionClouds, [switch]$DisableContextWorkarounds, [switch]$DefaultRuntimeOptions, [int]$Width=1280, [int]$Height=720, [string[]]$AdditionalJvmArguments=@(), [string]$JavaExecutable, [string]$DistantHorizonsJar, [switch]$ShaderDebug, [ValidateSet('Unbound','Reimagined')][string]$Shader='Unbound')
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot
$snapshotProfile=Join-Path $root 'evidence\accepted-profile'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$jarArchive=[IO.Compression.ZipFile]::OpenRead([IO.Path]::GetFullPath($CandidateJar))
try {
    $reader=[IO.StreamReader]::new($jarArchive.GetEntry('fabric.mod.json').Open())
    try {$jarVersion=($reader.ReadToEnd() | ConvertFrom-Json).version}finally{$reader.Dispose()}
} finally {$jarArchive.Dispose()}
if($jarVersion -ne $ModVersion){throw 'Requested version differs from the actual candidate JAR metadata'}
$runRoot=Join-Path $PSScriptRoot ('runs\'+$RunLabel)
$buildRoot=Join-Path $runRoot 'build'
if(Test-Path -LiteralPath $runRoot){throw 'RunLabel already exists; preserve previous evidence'}
$profile = [IO.Path]::GetFullPath($Profile)
$game = Join-Path $runRoot 'validation-game'
foreach($capture in @('01-day.png','02-sunset.png','03-rain.png','04-inside.png','05-night.png','06-water-reflection.png','07-landscape.png','08-closeup.png','09-above.png','10-evolving.png','11-camera-disocclusion.png','12-weather-transition.png','13-opaque-edge.png','14-dh-opaque-edge.png')) {
    $oldCapture=Join-Path $game "screenshots\$capture"
    if(Test-Path -LiteralPath $oldCapture) {Remove-Item -LiteralPath $oldCapture}
}
if (Test-Path -LiteralPath (Join-Path $buildRoot 'validation.exitcode')) {
    Remove-Item -LiteralPath (Join-Path $buildRoot 'validation.exitcode')
}
$jdk = Join-Path $JavaHome 'bin'
$classes = Join-Path $buildRoot 'validation-classes'
foreach ($directory in @($classes, $game, (Join-Path $game 'mods'), (Join-Path $game 'config'), (Join-Path $game 'shaderpacks'))) {
    [IO.Directory]::CreateDirectory($directory) | Out-Null
}
$fixtureManifestHash=$null
if($FixtureWorld){
 $fixturePath=[IO.Path]::GetFullPath($FixtureWorld)
 $fixturePrefix=[IO.Path]::GetFullPath((Join-Path $root 'fixtures'))+[IO.Path]::DirectorySeparatorChar
 if(-not $fixturePath.StartsWith($fixturePrefix,[StringComparison]::OrdinalIgnoreCase)){throw 'World fixture must be a frozen snapshot inside this laboratory'}
 $manifestPath=Join-Path (Split-Path $fixturePath) 'world-manifest.json'
 $fixtureManifest=Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
 if($fixtureManifest.bytes -gt 1GB){throw 'Unexpectedly large world fixture'}
 $worldTarget=Join-Path $game 'saves\Cloudscape-Validation'
 foreach($entry in $fixtureManifest.files){
  $target=Join-Path $worldTarget $entry.path
  New-Item -ItemType Directory -Path (Split-Path $target) -Force | Out-Null
  Copy-Item -LiteralPath (Join-Path $fixturePath $entry.path) -Destination $target
  if((Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash -ne $entry.sha256){throw 'Copied fixture does not match frozen world'}
 }
 $fixtureManifestHash=(Get-FileHash -LiteralPath $manifestPath -Algorithm SHA256).Hash
}
$AdditionalJvmArguments+="-Dcloudscape.validation.expectedFpsCap=$MaxFps"
$classpath = @(Get-Content -LiteralPath (Join-Path $root 'evidence\cloudscape-classpath.json') -Raw | ConvertFrom-Json)
$classpath += Join-Path $profile 'mods\iris-fabric-1.11.7+mc26.3.jar'
$compileClasspath = @($classpath)+@($CandidateJar)
$sources = @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'validation\java') -Filter '*.java' -Recurse | ForEach-Object FullName)
if($AdditionalJvmArguments -notcontains '-Dcloudscape.validation.ui=true'){$sources=@($sources | Where-Object { [IO.Path]::GetFileName($_) -ne 'UiGameCheck.java' })}
$snapshotRoot=Join-Path $buildRoot 'harness-source';$sourceManifest=@()
$sources=@(foreach($source in $sources){
    $relative=[IO.Path]::GetRelativePath((Join-Path $PSScriptRoot 'validation'),$source)
    $target=Join-Path $snapshotRoot $relative
    New-Item -ItemType Directory -Path (Split-Path $target) -Force|Out-Null
    Copy-Item -LiteralPath $source -Destination $target
    $sourceManifest+=@{path=$relative;sha256=(Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash}
    $target
})
$sourceManifest|ConvertTo-Json -Depth 4|Set-Content -LiteralPath (Join-Path $buildRoot 'harness-source-manifest.json') -Encoding utf8
& (Join-Path $jdk 'javac.exe') -J-XX:ActiveProcessorCount=2 --release 25 -proc:none -cp ($compileClasspath -join ';') -d $classes @sources
if ($LASTEXITCODE -ne 0) { throw 'Validation compilation failed' }
Copy-Item -Path (Join-Path $PSScriptRoot 'validation\resources\*') -Destination $classes -Force
& (Join-Path $jdk 'jar.exe') ("-J-Djava.io.tmpdir=$classes") --create --file (Join-Path $game 'mods\cloudscape-validation.jar') -C $classes .
foreach ($mod in @('iris-fabric-1.11.7+mc26.3.jar','sodium-fabric-0.9.2+mc26.3.jar','fabric-api-0.163.0+26.3.jar','lithium-fabric-0.26.2+mc26.3.jar')) {
    Copy-Item -LiteralPath (Join-Path $profile "mods\$mod") -Destination (Join-Path $game 'mods') -Force
}
if ($FullProfile) {
    foreach($name in @('lithium.properties','sodium-mixins.properties','modmenu.json','yacl.json5')) {
        $profileConfig=Join-Path $snapshotProfile ('config/'+$name)
        if(Test-Path -LiteralPath $profileConfig){Copy-Item -LiteralPath $profileConfig -Destination (Join-Path $game 'config') -Force}
    }
    Get-ChildItem -LiteralPath (Join-Path $profile 'mods') -Filter '*.jar' -File | Where-Object Name -NotLike 'cloudscape-volumetric-*' | Copy-Item -Destination (Join-Path $game 'mods') -Force
    Copy-Item -LiteralPath (Join-Path $profile 'config\betterclouds-v1.json') -Destination (Join-Path $game 'config') -Force
}
foreach($oldDh in Get-ChildItem -LiteralPath (Join-Path $game 'mods') -Filter 'DistantHorizons-*.jar' -File) {Remove-Item -LiteralPath $oldDh.FullName}
if($DistantHorizonsJar) {
    if(-not (Test-Path -LiteralPath $DistantHorizonsJar -PathType Leaf)){throw 'Distant Horizons test JAR is missing'}
    Copy-Item -LiteralPath $DistantHorizonsJar -Destination (Join-Path $game 'mods') -Force
    Copy-Item -LiteralPath (Join-Path $snapshotProfile 'config\DistantHorizons.toml') -Destination (Join-Path $game 'config')
    # Matches the accepted isolated test: avoid DH chat glyph uploads inside
    # an Iris render pass. Only the test copy changes; no rendering option changes.
    $dhConfigFile=Join-Path $game 'config\DistantHorizons.toml'
    $dhText=[IO.File]::ReadAllText($dhConfigFile)
    $dhText=[regex]::Replace($dhText,'(?m)^(\s*showHighVanillaRenderDistanceWarning\s*=\s*)true\s*$','${1}false')
    [IO.File]::WriteAllText($dhConfigFile,$dhText,[Text.UTF8Encoding]::new($false))
    $AdditionalJvmArguments+=@('-Dcloudscape.validation.distantHorizons=true')
}
if($BetterClouds) {
    if(-not $Baseline -or -not $FullProfile){throw 'Better Clouds comparison requires the full profile with Cloudscape disabled'}
    $betterConfigPath=Join-Path $game 'config\betterclouds-v1.json'
    $betterConfig=Get-Content -LiteralPath $betterConfigPath -Raw | ConvertFrom-Json
    $betterConfig.enabled=$true;$betterConfig.cloudOverride=$true;$betterConfig.irisSupport=$true
    [IO.File]::WriteAllText($betterConfigPath,($betterConfig | ConvertTo-Json -Depth 10),[Text.UTF8Encoding]::new($false))
} elseif($FullProfile) {
    $betterConfigPath=Join-Path $game 'config\betterclouds-v1.json'
    $betterConfig=Get-Content -LiteralPath $betterConfigPath -Raw | ConvertFrom-Json
    $betterConfig.cloudOverride=$false
    if($Baseline){$betterConfig.enabled=$false}
    [IO.File]::WriteAllText($betterConfigPath,($betterConfig | ConvertTo-Json -Depth 10),[Text.UTF8Encoding]::new($false))
}
if($CubicPlus) {
    if(-not $BetterClouds){throw 'Cubic Plus comparison requires Better Clouds'}
    [IO.Directory]::CreateDirectory((Join-Path $game 'resourcepacks')) | Out-Null
    Copy-Item -LiteralPath (Join-Path $profile 'resourcepacks\Cloudscape-Cubic-Plus.zip') -Destination (Join-Path $game 'resourcepacks') -Force
}
Get-ChildItem -LiteralPath (Join-Path $game 'mods') -Filter 'cloudscape-volumetric-*.jar' -File | Remove-Item
$cloudscapeVersion=$ModVersion
if($ModVersion) {
    if($ModVersion -notmatch '^\d+\.\d+\.\d+(?:-[a-zA-Z0-9.]+)?$'){throw 'Invalid validation mod version'}
    $cloudscapeVersion=$ModVersion
}
$legacyAdapter=[version]($cloudscapeVersion -split '-')[0] -lt [version]'0.7.0'
Copy-Item -LiteralPath $CandidateJar -Destination (Join-Path $game 'mods') -Force
$packName = "Complementary${Shader}_r5.9.3.zip"
Copy-Item -LiteralPath (Join-Path $profile "shaderpacks\$packName") -Destination (Join-Path $game 'shaderpacks') -Force
if($UnsupportedFixture) {
    # This copy remains valid GLSL but intentionally changes the contracted wrapper.
    $fixtureZip=[IO.Compression.ZipFile]::Open((Join-Path $game "shaderpacks\$packName"),'Update')
    try {
        $entry=$fixtureZip.GetEntry('shaders/lib/atmospherics/clouds/mainClouds.glsl')
        $reader=[IO.StreamReader]::new($entry.Open())
        try {$original=$reader.ReadToEnd()}finally{$reader.Dispose()}
        $entry.Delete()
        $writer=[IO.StreamWriter]::new($fixtureZip.CreateEntry('shaders/lib/atmospherics/clouds/mainClouds.glsl').Open(),[Text.UTF8Encoding]::new($false))
        try {$writer.Write($original+"`nconst float CLOUDSCAPE_UNSUPPORTED_FIXTURE = 1.0;`n")}finally{$writer.Dispose()}
    } finally {$fixtureZip.Dispose()}
}
$utf8 = [Text.UTF8Encoding]::new($false)
$iris = "enableShaders=true`nshaderPack=$packName`ndisableUpdateMessage=true`nallowUnknownShaders=false`n"
if(Test-Path -LiteralPath (Join-Path $snapshotProfile 'config/iris.properties')) {
    $iris=[IO.File]::ReadAllText((Join-Path $snapshotProfile 'config/iris.properties'))
    $iris=[regex]::Replace($iris,'(?m)^shaderPack=.*$',"shaderPack=$packName")
    $iris=[regex]::Replace($iris,'(?m)^enableShaders=.*$','enableShaders=true')
    $iris=[regex]::Replace($iris,'(?m)^disableUpdateMessage=.*$','disableUpdateMessage=true')
}
if($ShaderDebug){$iris+="enableDebugOptions=true`n"}
[IO.File]::WriteAllText((Join-Path $game 'config\iris.properties'), $iris, $utf8)
$cloudStyle = if ($Shader -eq 'Reimagined') {'1'} else {'3'}
$cloudAltitude = if ($Shader -eq 'Reimagined') {256} else {320}
$cloudThickness = if ($Shader -eq 'Reimagined') {128} else {180}
$shaderOptions = "CLOUD_QUALITY=3`nCLOUD_STYLE_DEFINE=$cloudStyle`nCLOUD_ALT1=$cloudAltitude`nDOUBLE_REIM_CLOUDS=false`nCLOUD_SHADOWS=true`nWATER_REFLECT_QUALITY=2`nSKY_EFFECT_REFLECTION_DEFINE=1`n"
if($UseProfileShaderOptions) {
    $profileShaderOptions=Join-Path $snapshotProfile "shaderpacks\$packName.txt"
    if(Test-Path -LiteralPath $profileShaderOptions) {$shaderOptions=[IO.File]::ReadAllText($profileShaderOptions)}
}
foreach($override in $ShaderOptionOverrides) {
    if($override -notmatch '^[A-Za-z][A-Za-z0-9_]*=(true|false|-?\d+(\.\d+)?)$'){throw 'Invalid isolated shader override'}
    $key=($override -split '=',2)[0]
    $pattern='(?m)^'+[regex]::Escape($key)+'=.*$'
    if($shaderOptions -match $pattern){$shaderOptions=[regex]::Replace($shaderOptions,$pattern,$override)}
    else{$shaderOptions+="`n$override"}
}
[IO.File]::WriteAllText((Join-Path $game "shaderpacks\$packName.txt"), $shaderOptions, $utf8)
if (-not (Test-Path -LiteralPath (Join-Path $game 'options.txt'))) {
    Copy-Item -LiteralPath (Join-Path $snapshotProfile 'options.txt') -Destination $game
}
$options = [IO.File]::ReadAllText((Join-Path $game 'options.txt'))
if($RenderDistance -lt 4 -or $RenderDistance -gt 32){throw 'Invalid render distance'}
if($MaxFps -lt 30 -or $MaxFps -gt 260){throw 'Invalid FPS limit'}
$resourcePackOptions=if($CubicPlus){'["vanilla","file/Cloudscape-Cubic-Plus.zip"]'}else{'[]'}
foreach ($setting in @{'renderDistance'=[string]$RenderDistance;'simulationDistance'=[string]$SimulationDistance;'maxFps'=[string]$MaxFps;'inactivityFpsLimit'='"minimized"';'pauseOnLostFocus'='false';'fullscreen'=$Fullscreen.ToString().ToLowerInvariant();'exclusiveFullscreen'='false';'onboardAccessibility'='false';'soundCategory_master'='0.0';'resourcePacks'=$resourcePackOptions;'incompatibleResourcePacks'='[]'}.GetEnumerator()) {
    $pattern = '(?m)^' + [regex]::Escape($setting.Key) + ':.*$'
    if ($options -match $pattern) { $options=[regex]::Replace($options,$pattern,($setting.Key+':'+$setting.Value)) }
    else { $options += "`n$($setting.Key):$($setting.Value)" }
}
[IO.File]::WriteAllText((Join-Path $game 'options.txt'), $options, $utf8)
if (Test-Path -LiteralPath (Join-Path $profile 'config\sodium-options.json')) {
    Copy-Item -LiteralPath (Join-Path $profile 'config\sodium-options.json') -Destination (Join-Path $game 'config') -Force
}
$configFile = Join-Path $game 'config\cloudscape-volumetric.json'
if($RawCloudConfig -and $MissingCloudConfig){throw 'Conflicting config fixture modes'}
if($MissingCloudConfig) { } elseif($RawCloudConfig) { Copy-Item -LiteralPath $CloudConfigFile -Destination $configFile } elseif ($Baseline) { [IO.File]::WriteAllText($configFile, '{"enabled":false}', $utf8) }
else {
    $configSource=if($CloudConfigFile){[IO.Path]::GetFullPath($CloudConfigFile)}else{$configFile}
    $config = if(Test-Path -LiteralPath $configSource){Get-Content -LiteralPath $configSource -Raw | ConvertFrom-Json}else{[pscustomobject]@{enabled=$true}}
    $config.enabled=$true
    $config | Add-Member -NotePropertyName 'reimaginedHalfResolution' -NotePropertyValue (-not $FullResolutionClouds) -Force
    [IO.File]::WriteAllText($configFile,($config | ConvertTo-Json),$utf8)
}
$version = Get-Content -LiteralPath $VersionJson -Raw | ConvertFrom-Json
$cleanInstallation=$AdditionalJvmArguments -contains '-Dcloudscape.validation.cleanInstallation=true'
if($cleanInstallation){
    foreach($optionalMod in @('fabric-api-0.163.0+26.3.jar','lithium-fabric-0.26.2+mc26.3.jar')){
        $optionalPath=Join-Path $game ('mods/'+$optionalMod)
        if(Test-Path -LiteralPath $optionalPath){Remove-Item -LiteralPath $optionalPath}
    }
    foreach($file in Get-ChildItem -LiteralPath (Join-Path $game 'config') -File){
        if($file.Name -notin @('iris.properties','cloudscape-volumetric.json')){Remove-Item -LiteralPath $file.FullName}
    }
    [IO.File]::WriteAllText((Join-Path $game 'options.txt'),"lang:en_us`nrenderDistance:16`nsimulationDistance:6`nmaxFps:180`nenableVsync:false`nfullscreen:true`nexclusiveFullscreen:false`npauseOnLostFocus:false`nonboardAccessibility:false`nsoundCategory_master:0.0`n",$utf8)
}
$profileStackArguments = @($version.arguments.jvm | Where-Object {$_ -is [string] -and $_ -match '^-XX:StackShadowPages=\d+$'})
if($cleanInstallation -or $profileStackArguments.Count -eq 0){$profileStackArguments=@('-XX:StackShadowPages=32')}
if($profileStackArguments.Count -gt 1){throw 'Ambiguous profile stack shadow settings'}
if(@($AdditionalJvmArguments | Where-Object {$_ -match '^-XX:StackShadowPages='}).Count){$profileStackArguments=@()}
$java = Join-Path $JavaHome 'bin/java.exe'
if ($JavaExecutable) { $java = $JavaExecutable }
if ($Width -lt 640 -or $Width -gt 3840 -or $Height -lt 360 -or $Height -gt 2160) { throw 'Validation resolution out of bounds' }
$natives = Join-Path $game 'natives'
[IO.Directory]::CreateDirectory($natives) | Out-Null
$diagnosticArguments = if ($DefaultRuntimeOptions) { @() } else { @('-Dsodium.checks.issue2048=false','-Dorg.lwjgl.system.allocator=system') }
$arguments = @('-Xms1G','-Xmx6G',('-XX:ErrorFile='+(Join-Path $buildRoot 'hs_err_pid%p.log')),'-XX:HeapDumpPath=MojangTricksIntelDriversForPerformance_javaw.exe_minecraft.exe.heapdump','--sun-misc-unsafe-memory-access=allow','--enable-native-access=ALL-UNNAMED','--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED') + $profileStackArguments + @(
    '-Dcloudscape.validation=true',('-Dcloudscape.validation.performance='+$Performance.ToString().ToLowerInvariant()),('-Dcloudscape.validation.opaqueEdge='+$OpaqueEdge.ToString().ToLowerInvariant()),('-Dcloudscape.validation.landscape='+$Landscape.ToString().ToLowerInvariant()),('-Dcloudscape.validation.motion='+$Motion.ToString().ToLowerInvariant()),('-Dcloudscape.validation.insideY='+($cloudAltitude+$cloudThickness*0.25)),('-Dcloudscape.validation.aboveY='+($cloudAltitude+$cloudThickness+176)),('-Dcloudscape.validation.quick='+$Quick.ToString().ToLowerInvariant())) + $AdditionalJvmArguments + $diagnosticArguments + @(
    ('-Djava.library.path='+$natives+'/java'),('-Djna.tmpdir='+$natives+'/jna'),
    ('-Dorg.lwjgl.system.SharedLibraryExtractPath='+$natives+'/lwjgl'),('-Dio.netty.native.workdir='+$natives+'/netty'),
    '-DFabricMcEmu= net.minecraft.client.main.Main ','-cp',($classpath -join ';'),$version.mainClass,
    '--username','CloudscapeTest','--version','Cloudscape-Validation','--gameDir',$game,
    '--assetsDir',(Join-Path $MinecraftHome 'assets'),'--assetIndex',$version.assetIndex.id,
    '--uuid','00000000000000000000000000000000','--accessToken','0','--versionType','modified',
    '--width',[string]$Width,'--height',[string]$Height,'--demo')
$quoted = $arguments | ForEach-Object { '"' + $_.Replace('\','\\').Replace('"','\"') + '"' }
$argumentFile=Join-Path $buildRoot 'validation.args'
[IO.File]::WriteAllLines($argumentFile,$quoted,$utf8)
$inputs=[ordered]@{version=$ModVersion;jarSHA256=(Get-FileHash -LiteralPath $CandidateJar -Algorithm SHA256).Hash;renderDistance=$RenderDistance;simulationDistance=$SimulationDistance;maxFps=$MaxFps;width=$Width;height=$Height;shader=$Shader;fullProfile=[bool]$FullProfile;fullResolution=[bool]$FullResolutionClouds;fixtureManifestSHA256=$fixtureManifestHash;profileStackArguments=$profileStackArguments;additionalJvmArguments=$AdditionalJvmArguments;files=@()}
foreach($inputFile in @(Get-ChildItem -LiteralPath (Join-Path $game 'mods') -File -Filter '*.jar')+@(Get-ChildItem -LiteralPath (Join-Path $game 'shaderpacks') -File)+@(Get-ChildItem -LiteralPath (Join-Path $game 'config') -File)+@(Get-Item -LiteralPath (Join-Path $game 'options.txt'))){
    $inputs.files+=@([pscustomobject]@{path=$inputFile.FullName.Substring($game.Length+1);sha256=(Get-FileHash -LiteralPath $inputFile.FullName -Algorithm SHA256).Hash})
}
$inputs | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $buildRoot 'inputs.json') -Encoding utf8
$mixinPath=Join-Path $game 'config\sodium-mixins.properties'
$hadMixinFile=Test-Path -LiteralPath $mixinPath
$mixinBytes=if($hadMixinFile){[IO.File]::ReadAllBytes($mixinPath)}else{$null}
try {
    if($DisableContextWorkarounds) {
        $mixinText=if($hadMixinFile){[Text.Encoding]::UTF8.GetString($mixinBytes)}else{''}
        $mixinText=[regex]::Replace($mixinText,'(?m)^mixin\.workarounds\.context_creation=.*\r?\n?','')
        [IO.File]::WriteAllText($mixinPath,$mixinText+"`nmixin.workarounds.context_creation=false`n",$utf8)
    }
    $process=Start-Process -FilePath $java -ArgumentList ('@"'+$argumentFile+'"') -WorkingDirectory $game -WindowStyle Hidden -RedirectStandardOutput (Join-Path $buildRoot 'validation-stdout.log') -RedirectStandardError (Join-Path $buildRoot 'validation-stderr.log') -PassThru
    [IO.File]::WriteAllText((Join-Path $buildRoot 'validation.pid'),[string]$process.Id)
    Write-Output "Isolated validation launched: PID $($process.Id)"
    $process.WaitForExit()
    [IO.File]::WriteAllText((Join-Path $buildRoot 'validation.exitcode'), [string]$process.ExitCode)
} finally {
    if($DisableContextWorkarounds) {
        if($hadMixinFile){[IO.File]::WriteAllBytes($mixinPath,$mixinBytes)}
        elseif(Test-Path -LiteralPath $mixinPath){Remove-Item -LiteralPath $mixinPath}
    }
}
Write-Output "Validation exited: $($process.ExitCode)"
if ($process.ExitCode -ne 0) { throw "Validation process failed: $($process.ExitCode)" }
$validationLog=[IO.File]::ReadAllText((Join-Path $buildRoot 'validation-stdout.log'))
if(-not $validationLog.Contains('CLOUDSCAPE VALIDATION COMPLETE')) {throw 'Validation did not complete its scene sequence'}
if($validationLog -match 'Failed to create shader rendering pipeline|Shader compilation failed|ShaderCompileException') {throw 'Shader pipeline failed during validation'}
if($UnsupportedFixture) {
    if(-not $validationLog.Contains('CLOUDSCAPE UNSUPPORTED PASS original pack active without atlas')){throw 'Unsupported fixture was not verified in the actual pipeline'}
    if($validationLog.Contains('Active ray-marched volume adapter:')){throw 'Unsupported fixture was unexpectedly patched'}
} elseif(-not $Baseline) {
    $requiredAdapters=@($(if($Shader -eq 'Reimagined'){'reimaginedClouds.glsl'}else{'unboundClouds.glsl'}),'cloudShadows.glsl')
    if([version]($cloudscapeVersion -split '-')[0] -ge [version]'0.8.3'){$requiredAdapters+='mainClouds.glsl'}
    if(-not $legacyAdapter){$requiredAdapters+=@('taa.glsl','composite6.glsl','pipelineSettings.glsl','rainbow.glsl','volumetricLight.glsl')}
    foreach($adaptedSource in $requiredAdapters) {
        if($validationLog -notmatch ('Active ray-marched volume adapter: [^\r\n]*'+[regex]::Escape($adaptedSource))) {
            throw "The game ran without the expected Cloudscape adapter: $adaptedSource"
        }
    }
    if($Shader -eq 'Reimagined' -and -not $FullResolutionClouds -and -not $legacyAdapter) {
        foreach($marker in @('Active half-resolution cloud cache:','Active half-resolution cloud cache stage: shaders/world0/deferred.fsh','Active half-resolution cloud cache stage: shaders/world0/deferred.vsh','Active ray-marched volume adapter: shaders/program/deferred1.glsl')) {
            if(-not $validationLog.Contains($marker)){throw "Half-resolution pipeline was not active: $marker"}
        }
        if($config.reimaginedVoxelCache -and $config.reimaginedVoxelSize -eq 16 -and -not $config.reimaginedUpperLayer) {
            foreach($marker in @('Active voxel-field cache stage: shaders/world0/prepare.fsh','Active voxel-field cache stage: shaders/world0/prepare1.fsh')) {
                if(-not $validationLog.Contains($marker)){throw "Voxel cache was not active: $marker"}
            }
            if($config.reimaginedCachedReflections -and -not $validationLog.Contains('Active cloud reflection cache stage: shaders/world0/prepare2.fsh')){throw 'Reflection cache was not active'}
        }
    } elseif($validationLog.Contains('Active half-resolution cloud cache:')) {throw 'Unexpected half-resolution pipeline in a full-resolution or Unbound test'}
}
Write-Output 'Validation scenes and active shader adapters verified.'

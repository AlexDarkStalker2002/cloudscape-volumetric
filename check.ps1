param(
 [Parameter(Mandatory=$true)][string]$MinecraftHome,
 [Parameter(Mandatory=$true)][string]$Profile,
 [string]$JavaHome=$env:JAVA_HOME,
 [string]$BuildLabel='release',
 [string]$ReferenceJar,
 [string]$JcppJar,
 [string]$RunLabel='cpu'
)
$ErrorActionPreference='Stop'
$project=$PSScriptRoot
$build=Join-Path $project ('build/'+$BuildLabel)
$manifest=Get-Content -LiteralPath (Join-Path $build 'build-manifest.json') -Raw|ConvertFrom-Json
$jar=Join-Path $build $manifest.jar
if((Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash -ne $manifest.sha256){throw 'Built JAR differs from manifest'}
$out=Join-Path $build $RunLabel
if(Test-Path -LiteralPath $out){throw 'Use a new RunLabel; preserve earlier checks'}
New-Item -ItemType Directory -Path $out|Out-Null
$lock=Get-Content -LiteralPath (Join-Path $project 'dependencies.lock.json') -Raw|ConvertFrom-Json
$cp=@(foreach($entry in $lock.dependencies){
 $base=if($entry.base -eq 'minecraft'){$MinecraftHome}else{$Profile};$p=Join-Path $base $entry.path
 if((Get-FileHash -LiteralPath $p -Algorithm SHA256).Hash -ne $entry.sha256){throw "Changed dependency: $($entry.path)"};$p
})+@($jar,$out)
if(-not $JcppJar){
 Add-Type -AssemblyName System.IO.Compression.FileSystem
 $zip=[IO.Compression.ZipFile]::OpenRead((Join-Path $Profile 'mods/iris-fabric-1.11.7+mc26.3.jar'))
 try{
  $entry=@($zip.Entries|Where-Object {$_.FullName -match 'jcpp.*\.jar$'})
  if($entry.Count -ne 1){throw 'Pass -JcppJar; Iris must contain exactly one jcpp dependency'}
  $JcppJar=Join-Path $out 'jcpp.jar';[IO.Compression.ZipFileExtensions]::ExtractToFile($entry[0],$JcppJar)
 }finally{$zip.Dispose()}
}
$cp+=@($JcppJar)
$tests=@('SettingsCheck','AdapterCheck','InterfaceCheck','WorldDensityCacheCheck','AtlasStyleCheck','DiagnosticsCheck','IntegrationCacheCheck','WeatherCheck','WeatherEpisodeCheck','ObservedWindTickCheck','WindPublicationCheck')
if($ReferenceJar){$tests+=@('SourceParityCheck')}
$sources=@($tests|ForEach-Object {Join-Path $project "validation/cpu/$_.java"})
& (Join-Path $JavaHome 'bin/javac.exe') -J-XX:ActiveProcessorCount=2 --release 25 -proc:none -cp ($cp -join ';') -d $out @sources
if($LASTEXITCODE -ne 0){throw 'Check compilation failed'}
$reimagined=Join-Path $Profile 'shaderpacks/ComplementaryReimagined_r5.9.3.zip'
$unbound=Join-Path $Profile 'shaderpacks/ComplementaryUnbound_r5.9.3.zip'
$results=@()
foreach($test in $tests){
 $argsTest=@(switch($test){
  'SettingsCheck' {@((Join-Path $out 'config-fixtures'))}
  'WeatherCheck' {@((Join-Path $out 'weather-fixtures'))}
  'WorldDensityCacheCheck' {@((Join-Path $out 'cache-fixtures'),$reimagined,$unbound)}
  'WeatherEpisodeCheck' {@((Join-Path $out 'episodes'))}
  'SourceParityCheck' {@($ReferenceJar)}
  default {@($reimagined,$unbound)}
 })
 & (Join-Path $JavaHome 'bin/java.exe') -XX:ActiveProcessorCount=2 ("-Djava.io.tmpdir=$out") -cp ($cp -join ';') "dev.cloudscape.volumetric.$test" @argsTest 2>&1|Tee-Object -FilePath (Join-Path $out "$test.log")
 if($LASTEXITCODE -ne 0){throw "$test failed"}
 $results+=@{test=$test;passed=$true}
}
@{jarSHA256=$manifest.sha256;tests=$results;sourceParitySkipped=(-not $ReferenceJar);passed=$true}|ConvertTo-Json -Depth 5|Set-Content -LiteralPath (Join-Path $out 'result.json') -Encoding utf8

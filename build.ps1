param(
 [Parameter(Mandatory=$true)][string]$MinecraftHome,
 [Parameter(Mandatory=$true)][string]$Profile,
 [string]$JavaHome=$env:JAVA_HOME,
 [ValidatePattern('^[A-Za-z0-9_-]+$')][string]$BuildLabel='release'
)
$ErrorActionPreference='Stop'
$project=$PSScriptRoot
if(-not $JavaHome){throw 'Pass -JavaHome pointing to JDK 25 (or set JAVA_HOME).'}
$javac=Join-Path $JavaHome 'bin/javac.exe';$jarTool=Join-Path $JavaHome 'bin/jar.exe'
if(-not(Test-Path -LiteralPath $javac)){throw 'JDK compiler missing'}
$metadata=Get-Content -LiteralPath (Join-Path $project 'src/main/resources/fabric.mod.json') -Raw|ConvertFrom-Json
$version=$metadata.version
$lock=Get-Content -LiteralPath (Join-Path $project 'dependencies.lock.json') -Raw|ConvertFrom-Json
$cp=@(foreach($entry in $lock.dependencies){
 $base=if($entry.base -eq 'minecraft'){$MinecraftHome}elseif($entry.base -eq 'profile'){$Profile}else{throw 'Unknown dependency base'}
 $path=Join-Path $base $entry.path
 if(-not(Test-Path -LiteralPath $path -PathType Leaf)){throw "Install locked dependency: $($entry.path)"}
 if((Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash -ne $entry.sha256){throw "Dependency differs from tested bytes: $($entry.path)"}
 [IO.Path]::GetFullPath($path)
})
$assets=Join-Path $project 'src/main/resources/assets/cloudscape_volumetric'
$field=[IO.File]::ReadAllText((Join-Path $assets 'field-shapes.glsl')).Replace("`r`n","`n")
foreach($name in @('density.glsl','density-atlas.glsl')){
 $text=[IO.File]::ReadAllText((Join-Path $assets $name)).Replace("`r`n","`n")
 $start='// BEGIN GENERATED SHAPES: tools/sync-field.py'+"`n"
 $a=$text.IndexOf($start)+$start.Length;$b=$text.IndexOf('// END GENERATED SHAPES',$a)
 if($a -lt $start.Length -or $b -lt $a -or $text.Substring($a,$b-$a) -cne $field){throw "$name differs from shared shape field"}
}
$output=Join-Path $project ('build/'+$BuildLabel)
if(Test-Path -LiteralPath $output){throw 'Use a new BuildLabel; preserve previous build evidence.'}
$classes=Join-Path $output 'classes';New-Item -ItemType Directory -Path $classes -Force|Out-Null
$sources=@(Get-ChildItem -LiteralPath (Join-Path $project 'src/main/java') -File -Recurse -Filter '*.java'|Sort-Object FullName|ForEach-Object FullName)
& $javac -J-XX:ActiveProcessorCount=2 --release 25 -proc:none -cp ($cp -join ';') -d $classes @sources
if($LASTEXITCODE -ne 0){throw 'Java compilation failed'}
Copy-Item -Path (Join-Path $project 'src/main/resources/*') -Destination $classes -Recurse
Copy-Item -LiteralPath (Join-Path $project 'LICENSE') -Destination $classes
$jarPath=Join-Path $output "cloudscape-volumetric-$version+mc26.3.jar"
& $jarTool --create --date=2026-10-24T00:00:00Z --file $jarPath -C $classes .
if($LASTEXITCODE -ne 0){throw 'JAR packaging failed'}
$manifest=[ordered]@{version=$version;jar=[IO.Path]::GetFileName($jarPath);sha256=(Get-FileHash -LiteralPath $jarPath -Algorithm SHA256).Hash;dependenciesLockSHA256=(Get-FileHash -LiteralPath (Join-Path $project 'dependencies.lock.json') -Algorithm SHA256).Hash;javaCompiler=(& $javac -version 2>&1|Out-String).Trim();source=@()}
foreach($file in Get-ChildItem -LiteralPath (Join-Path $project 'src') -File -Recurse|Sort-Object FullName){
 $manifest.source+=@{path=[IO.Path]::GetRelativePath($project,$file.FullName).Replace('\','/');sha256=(Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash}
}
$manifest|ConvertTo-Json -Depth 6|Set-Content -LiteralPath (Join-Path $output 'build-manifest.json') -Encoding utf8
Write-Output $jarPath

param([string]$BuildLabel='rc1',[Parameter(Mandatory=$true)][string]$AcceptanceFile,[string]$Destination)
$ErrorActionPreference='Stop'
$build=Join-Path $PSScriptRoot ('build/'+$BuildLabel)
$manifest=Get-Content -LiteralPath (Join-Path $build 'build-manifest.json') -Raw|ConvertFrom-Json
$acceptance=Get-Content -LiteralPath $AcceptanceFile -Raw|ConvertFrom-Json
$jar=Join-Path $build $manifest.jar
if((Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash -ne $manifest.sha256 -or $acceptance.jarSHA256 -ne $manifest.sha256 -or -not $acceptance.readyForLocalRC){throw 'Packaging requires acceptance for the exact JAR'}
if(@(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'src') -File -Recurse).Count -ne @($manifest.source).Count){throw 'Source file set changed after build'}
foreach($source in $manifest.source){
 if((Get-FileHash -LiteralPath (Join-Path $PSScriptRoot $source.path) -Algorithm SHA256).Hash -ne $source.sha256){throw "Source changed after accepted build: $($source.path)"}
}
if(-not $Destination){$Destination=Join-Path $PSScriptRoot ('dist/'+$manifest.version)}
if(Test-Path -LiteralPath $Destination){throw 'Use a new destination; preserve earlier distributions'}
New-Item -ItemType Directory -Path $Destination|Out-Null
Copy-Item -LiteralPath $jar,(Join-Path $PSScriptRoot 'LICENSE'),(Join-Path $PSScriptRoot 'README.md'),(Join-Path $PSScriptRoot 'README.en.md'),(Join-Path $PSScriptRoot 'README.ru.md'),(Join-Path $PSScriptRoot 'CHANGELOG.md'),(Join-Path $PSScriptRoot 'install.ps1'),(Join-Path $PSScriptRoot 'restore.ps1') -Destination $Destination
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'docs'),(Join-Path $PSScriptRoot 'presets') -Destination $Destination -Recurse
Copy-Item -LiteralPath $AcceptanceFile -Destination (Join-Path $Destination 'acceptance.json')
$items=@(Get-ChildItem -LiteralPath $Destination -File -Recurse|Sort-Object FullName|ForEach-Object {@{path=[IO.Path]::GetRelativePath([IO.Path]::GetFullPath($Destination),$_.FullName).Replace('\','/');sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash;bytes=$_.Length}})
@{version=$manifest.version;jar=$manifest.jar;jarSHA256=$manifest.sha256;type='release-candidate';files=$items}|ConvertTo-Json -Depth 6|Set-Content -LiteralPath (Join-Path $Destination 'manifest.json') -Encoding utf8
Compress-Archive -Path (Join-Path $Destination '*') -DestinationPath ($Destination+'.zip')
Write-Output $Destination

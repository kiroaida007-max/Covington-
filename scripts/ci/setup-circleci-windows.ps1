$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$ciRoot = (Resolve-Path "$PSScriptRoot/../..").Path
Set-Location $ciRoot
New-Item -ItemType Directory -Force .ci-tools | Out-Null
function Get-PinnedZip([string]$name, [string]$url, [string]$hash) {
    $archive = Join-Path $ciRoot ".ci-tools/$name.zip"
    Invoke-WebRequest -UseBasicParsing -Uri $url -OutFile $archive -TimeoutSec 300
    if ((Get-FileHash $archive -Algorithm SHA256).Hash.ToLowerInvariant() -ne $hash) {
        throw "CI bootstrap hash mismatch: $name"
    }
    Expand-Archive -Path $archive -DestinationPath ".ci-tools/$name" -Force
    Remove-Item $archive
}
Get-PinnedZip 'jdk17' 'https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.20.1%2B1/OpenJDK17U-jdk_x64_windows_hotspot_17.0.20.1_1.zip' 'e53a79c3c3d86865bd7e787903884331068e71321714ffd44f145785affc7cb0'
Get-PinnedZip 'python312' 'https://api.nuget.org/v3-flatcontainer/python/3.12.10/python.3.12.10.nupkg' '0eb85c2dfccccf1b17352de4c397f69194035b7d37149eacc16f1147d93de3b8'
$jdkDirs = @(Get-ChildItem .ci-tools/jdk17 -Directory)
if ($jdkDirs.Count -ne 1) { throw 'Expected exactly one pinned JDK directory' }
$java = $jdkDirs[0].FullName
$python = (Resolve-Path .ci-tools/python312/tools).Path
$envFile = @"
`$env:JAVA_HOME = '$($java.Replace("'", "''"))'
`$env:JDK17_HOME = `$env:JAVA_HOME
`$env:APKPROJECT_TOOLS = '$($ciRoot.Replace("'", "''"))/.tools'
`$env:APKPROJECT_PERFORMANCE = '1'
`$env:GITHUB_WORKSPACE = '$($ciRoot.Replace("'", "''"))'
`$env:PATH = '$($python.Replace("'", "''"));$($python.Replace("'", "''"))/Scripts;' + `$env:JAVA_HOME + '/bin;' + `$env:PATH
"@
Set-Content -Path .ci-env.ps1 -Value $envFile -Encoding UTF8
. ./.ci-env.ps1
python -c 'import sys; assert sys.version_info[:2] == (3, 12)'
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
python -m pip install PyYAML==6.0.2
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& "$env:JAVA_HOME/bin/java.exe" -version
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

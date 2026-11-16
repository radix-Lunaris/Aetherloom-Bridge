$ErrorActionPreference='Stop'
Set-Location $PSScriptRoot
if (-not $env:JAVA_HOME) { throw 'Set JAVA_HOME to JDK17' }
& ./gradlew.bat build --no-daemon --console plain
if ($LASTEXITCODE -ne 0) { throw 'Forge build failed' }
New-Item -ItemType Directory -Force release | Out-Null
Copy-Item build/libs/aetherloom-bridge-0.1.1.jar release/aetherloom-bridge-0.1.1.jar -Force
$hash=(Get-FileHash release/aetherloom-bridge-0.1.1.jar -Algorithm SHA256).Hash.ToLowerInvariant()
[IO.File]::WriteAllText((Join-Path $PSScriptRoot 'release/SHA256SUMS.txt'),$hash+'  aetherloom-bridge-0.1.1.jar'+[Environment]::NewLine,[Text.UTF8Encoding]::new($false))
$build=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot 'build'))
if ((Split-Path $build -Parent) -ne [IO.Path]::GetFullPath($PSScriptRoot)) { throw 'Invalid build cleanup path' }
if ((Get-Item -LiteralPath $build).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Refusing linked build cleanup' }
Remove-Item -LiteralPath $build -Recurse -Force

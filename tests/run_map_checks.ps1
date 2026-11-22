param(
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$GradleCache = (Join-Path $env:USERPROFILE '.gradle/caches')
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
if (-not $JavaHome) { throw '请通过 -JavaHome 指定 JDK 17 或以上目录，或设置 JAVA_HOME。' }
$stdlibRoot = Join-Path $GradleCache 'modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib/2.0.21'
$stdlib = Get-ChildItem -LiteralPath $stdlibRoot -Recurse -Filter 'kotlin-stdlib-2.0.21.jar' | Select-Object -First 1
if (-not $stdlib) { throw '未找到 Kotlin 标准库；请先执行 Android/gradlew.bat :app:assembleDebug。' }
$classes = Join-Path $projectRoot 'Android/app/build/tmp/kotlin-classes/debug'
if (-not (Test-Path -LiteralPath $classes)) { throw '请先编译 :app:assembleDebug。' }
$testOut = Join-Path $PSScriptRoot 'out'
New-Item -ItemType Directory -Path $testOut -Force | Out-Null
$classpath = $classes + [IO.Path]::PathSeparator + $stdlib.FullName
& (Join-Path $JavaHome 'bin/javac.exe') -encoding UTF-8 -cp $classpath -d $testOut (Join-Path $PSScriptRoot 'RoadMapChecks.java')
if ($LASTEXITCODE -ne 0) { throw '测试编译失败。' }
& (Join-Path $JavaHome 'bin/java.exe') -cp ($testOut + [IO.Path]::PathSeparator + $classpath) com.example.roundnav.RoadMapChecks
if ($LASTEXITCODE -ne 0) { throw '地图检查失败。' }

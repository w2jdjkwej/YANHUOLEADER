$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($env:JAVA_HOME)) {
    throw "未设置 JAVA_HOME。请将它指向 JDK 17 后重试。"
}

if ([string]::IsNullOrWhiteSpace($env:ANDROID_SDK_ROOT)) {
    if ([string]::IsNullOrWhiteSpace($env:ANDROID_HOME)) {
        throw "未设置 ANDROID_SDK_ROOT。请将它指向 Android SDK 后重试。"
    }
    $env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
}

$env:ANDROID_HOME = $env:ANDROID_SDK_ROOT
$buildUserHome = Join-Path $PSScriptRoot ".test-user-home"
$localAppData = Join-Path $buildUserHome "localappdata"
New-Item -ItemType Directory -Force -Path $buildUserHome, $localAppData | Out-Null
$env:ANDROID_USER_HOME = $buildUserHome
$env:LOCALAPPDATA = $localAppData
Remove-Item Env:ANDROID_SDK_HOME -ErrorAction SilentlyContinue
Remove-Item Env:ANDROID_PREFS_ROOT -ErrorAction SilentlyContinue
if ([string]::IsNullOrWhiteSpace($env:GRADLE_USER_HOME)) {
    $env:GRADLE_USER_HOME = Join-Path $PSScriptRoot ".gradle-user-home"
}
$env:Path = "$env:JAVA_HOME\bin;$env:ANDROID_SDK_ROOT\platform-tools;$env:Path"

& "$PSScriptRoot\gradlew.bat" @args
exit $LASTEXITCODE

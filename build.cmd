@echo off
REM ===========================================================================
REM  Cobblemon Player XP Share - Windows build script
REM
REM  Usage:
REM    build.cmd                 Build online (use this the first time)
REM    build.cmd --offline       Build using only already-cached dependencies
REM
REM  Notes:
REM    - GRADLE_USER_HOME points at .tmp\ghome inside this folder, so all Gradle
REM      state stays here and never touches %USERPROFILE%\.gradle.
REM    - The first build needs a network connection: it downloads the NeoForge /
REM      Minecraft / Kotlin dependencies. The Cobblemon jar is NEVER downloaded by
REM      this script - put your own copy in this folder or in libs\ (any file name
REM      containing "cobblemon" and ending in .jar).
REM    - After the dependencies are cached, --offline works.
REM    - Java 21 is required. Pointing JAVA_HOME at a JDK 21 is the safest setup.
REM ===========================================================================
setlocal

set "PROJECT_DIR=%~dp0"
cd /d "%PROJECT_DIR%"

if not exist ".tmp\ghome" mkdir ".tmp\ghome"
set "GRADLE_USER_HOME=%PROJECT_DIR%.tmp\ghome"

set "OFFLINE="
if /I "%~1"=="--offline" set "OFFLINE=--offline"

REM Prefer the Gradle wrapper; fall back to a system Gradle if it is missing.
if exist "%PROJECT_DIR%gradle\wrapper\gradle-wrapper.jar" (
    call gradlew.bat build %OFFLINE% %2 %3 %4 %5
) else (
    where gradle >nul 2>nul
    if errorlevel 1 (
        echo [ERROR] Neither gradle\wrapper\gradle-wrapper.jar nor a system Gradle was found.
        echo         Install Gradle, or run "gradle wrapper" in this folder to generate the wrapper.
        exit /b 1
    )
    call gradle build %OFFLINE% %2 %3 %4 %5
)

if errorlevel 1 (
    echo.
    echo [FAILED] The build did not succeed. Common causes:
    echo          - No network connection (the first build needs one; --offline needs warm caches)
    echo          - JAVA_HOME is not Java 21
    echo          - No Cobblemon jar found (put one in this folder or in libs\)
    exit /b 1
)

echo.
echo [DONE] Artifacts are in: %PROJECT_DIR%build\libs\
dir /b "%PROJECT_DIR%build\libs"
endlocal

@echo off
setlocal
set "SCRIPT_DIR=%~dp0"
set "JAR="
for %%f in ("%SCRIPT_DIR%nacosbase-*.jar") do set "JAR=%%f"
if not defined JAR (
    echo Error: nacosbase jar not found in %SCRIPT_DIR% 1>&2
    exit /b 1
)
java --enable-native-access=ALL-UNNAMED -jar "%JAR%" %*

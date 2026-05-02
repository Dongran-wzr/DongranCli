@echo off
setlocal ENABLEDELAYEDEXPANSION

set "SCRIPT_DIR=%~dp0"
if "%SCRIPT_DIR:~-1%"=="\" set "SCRIPT_DIR=%SCRIPT_DIR:~0,-1%"
set "BIN_DIR=%USERPROFILE%\bin"
set "JAR=%SCRIPT_DIR%\target\DongranCli-1.0-SNAPSHOT.jar"

echo [1/4] Checking jar...
if not exist "%JAR%" (
  echo Jar not found: %JAR%
  echo Build first: mvn clean package
  exit /b 1
)

echo [2/4] Creating bin dir...
if not exist "%BIN_DIR%" mkdir "%BIN_DIR%"

echo [3/4] Writing launchers...
> "%BIN_DIR%\dongran.cmd" (
  echo @echo off
  echo setlocal
  echo set "JAR=%JAR%"
  echo java -jar "%%JAR%%" %%*
)
> "%BIN_DIR%\dongrancli.cmd" (
  echo @echo off
  echo setlocal
  echo set "JAR=%JAR%"
  echo java -jar "%%JAR%%" %%*
)

echo [4/4] Updating PATH...
echo %PATH% | find /I "%BIN_DIR%" >nul
if errorlevel 1 (
  setx PATH "%PATH%;%BIN_DIR%" >nul
  echo PATH updated for user. Reopen terminal to take effect.
) else (
  echo PATH already contains %BIN_DIR%
)

echo Done. Try: dongran
exit /b 0

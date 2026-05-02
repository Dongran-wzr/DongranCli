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
set "PS_CMD=$bin=[Environment]::GetFolderPath('UserProfile') + '\\bin';"
set "PS_CMD=%PS_CMD%$userPath=[Environment]::GetEnvironmentVariable('Path','User');"
set "PS_CMD=%PS_CMD%$parts=@();"
set "PS_CMD=%PS_CMD%if(-not [string]::IsNullOrWhiteSpace($userPath)){ foreach($p in ($userPath -split ';')){ if(-not [string]::IsNullOrWhiteSpace($p)){ $parts += $p } } };"
set "PS_CMD=%PS_CMD%if($parts -contains $bin){ Write-Output 'PATH already contains user bin' }"
set "PS_CMD=%PS_CMD%else {"
set "PS_CMD=%PS_CMD%  $newPath = if($parts.Count -eq 0){ $bin } else { ($parts + $bin) -join ';' };"
set "PS_CMD=%PS_CMD%  [Environment]::SetEnvironmentVariable('Path',$newPath,'User');"
set "PS_CMD=%PS_CMD%  Write-Output 'PATH updated for user. Reopen terminal to take effect.'"
set "PS_CMD=%PS_CMD%}"

powershell -NoProfile -ExecutionPolicy Bypass -Command "%PS_CMD%"
if errorlevel 1 (
  echo Failed to update user PATH automatically.
  echo Please run in PowerShell:
  echo   [Environment]::SetEnvironmentVariable('Path', [Environment]::GetEnvironmentVariable('Path','User') + ';%BIN_DIR%', 'User')
)

echo Done. Try: dongran
exit /b 0

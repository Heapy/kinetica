@echo off
setlocal

set ROOT_DIR=%~dp0
if "%KOTLIN_CLI_BOOTSTRAP_CACHE_DIR%"=="" set KOTLIN_CLI_BOOTSTRAP_CACHE_DIR=%ROOT_DIR%\.kotlin\bootstrap
if "%KOTLIN_CLI_NO_WELCOME_BANNER%"=="" set KOTLIN_CLI_NO_WELCOME_BANNER=1
if not exist "%ROOT_DIR%\.kotlin\home" mkdir "%ROOT_DIR%\.kotlin\home"
set KOTLIN_CLI_JAVA_OPTIONS=%KOTLIN_CLI_JAVA_OPTIONS% -Duser.home=%ROOT_DIR%\.kotlin\home

kotlin --shared-cache-dir "%ROOT_DIR%\.kotlin\shared" %*

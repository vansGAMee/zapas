@echo off
setlocal
cd /d "%~dp0"
where py >nul 2>nul
if errorlevel 1 (
  echo Install Python 3.11 or newer from python.org, then run this file again.
  pause
  exit /b 1
)
if not exist .venv\Scripts\python.exe (
  py -3 -m venv .venv
  .venv\Scripts\python.exe -m pip install -r desktop\requirements.txt
  if errorlevel 1 (
    pause
    exit /b 1
  )
)
set "PYTHONPATH=%CD%\desktop"
.venv\Scripts\python.exe -m zapas %*
if errorlevel 1 pause

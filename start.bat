@echo off
setlocal
cd /d "%~dp0"

where py >nul 2>nul
if %errorlevel%==0 goto use_py

where python >nul 2>nul
if %errorlevel%==0 goto use_python

echo Python 3 was not found. Install Python 3 and try again.
pause
exit /b 1

:use_py
py -3 ms5_viewer.py
goto finished

:use_python
python ms5_viewer.py

:finished
echo.
echo ECC has stopped. Press any key to close this window.
pause >nul

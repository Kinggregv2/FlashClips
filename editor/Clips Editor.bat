@echo off
rem Starts the Clips editor (opens in your browser). Lives in the Minecraft instance folder.
cd /d "%~dp0"
set "FB="
for %%f in ("mods\Flashback-*.jar") do set "FB=%%~ff"
if not defined FB (
  echo Flashback was not found in the mods folder. The editor uses its video libraries.
  pause
  exit /b 1
)
set "EXTRA="
if defined CLIPS_NOBROWSER set "EXTRA=-Dclips.noBrowser=true"
start "Clips Editor" javaw --enable-native-access=ALL-UNNAMED %EXTRA% -cp "%~dp0clips-editor\clips-editor.jar;%FB%" dev.flashclips.editor.Main "%CD%"

@echo off
chcp 65001 >nul
echo Building AutoDungeon...
gradle build
echo.
echo Done. Jar is in build\libs\autodungeon-1.3.0.jar
pause

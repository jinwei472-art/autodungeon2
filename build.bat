@echo off
chcp 65001 >nul
echo Building AutoDungeon...
gradle build
echo.
echo Done. Jar is in build\libs\autodungeon-1.2.0.jar
pause

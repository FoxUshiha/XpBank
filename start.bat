@echo off
pushd %~dp0

REM limpa e recria pasta out
if exist out rd /s /q out
mkdir out

echo Compiling XpBank with Spigot-API and Vault...
javac -classpath "spigot-api-1.21.6-R0.1-SNAPSHOT.jar;Vault.jar" -d out ^
src\com\foxsrv\xpbank\XpBank.java ^
src\com\foxsrv\xpbank\XPBCommand.java ^
src\com\foxsrv\xpbank\PlayerData.java ^
src\com\foxsrv\xpbank\InterestTask.java ^
src\com\foxsrv\xpbank\VaultHook.java

if errorlevel 1 (
    echo Erro na compilacao!
    pause
    popd
    exit /b 1
)

echo Copying resources...
copy /Y plugin.yml out >nul
copy /Y config.yml out >nul
copy /Y players.yml out >nul

echo Packing JAR...
cd out
jar cvf XpBank.jar *
move /Y XpBank.jar "%~dp0"

echo Build concluido! XpBank.jar gerado na raiz.
pause
popd

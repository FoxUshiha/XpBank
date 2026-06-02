@echo off
title Compilador XPBank v1.1 - Java 17
echo ============================================
echo Compilador do Plugin XPBank
echo (Sistema de Deposito e Saque de XP)
echo ============================================
echo.
echo Procurando Java 17 instalado...
echo.
set JDK_PATH=
rem Procura JDK 17 em locais comuns
for /d %%i in ("C:\Program Files\Java\jdk-17*") do set JDK_PATH=%%i
for /d %%i in ("C:\Program Files\Java\jdk17*") do set JDK_PATH=%%i
for /d %%i in ("C:\Program Files\Eclipse Adoptium\jdk-17*") do set JDK_PATH=%%i
for /d %%i in ("C:\Program Files\AdoptOpenJDK\jdk-17*") do set JDK_PATH=%%i
for /d %%i in ("C:\Program Files\OpenJDK\jdk-17*") do set JDK_PATH=%%i
for /d %%i in ("C:\Program Files\Amazon Corretto\jdk17*") do set JDK_PATH=%%i
for /d %%i in ("C:\Program Files\Microsoft\jdk-17*") do set JDK_PATH=%%i

if "%JDK_PATH%"=="" (
    echo ============================================
    echo ERRO: JDK 17 nao encontrado!
    echo Instale o Java 17 JDK e tente novamente.
    echo ============================================
    pause
    exit /b 1
)
echo Java 17 encontrado em: %JDK_PATH%
echo.

set JAVAC="%JDK_PATH%\bin\javac.exe"
set JAR="%JDK_PATH%\bin\jar.exe"

echo ============================================
echo Preparando ambiente de compilacao...
echo ============================================
echo.

echo Limpando pasta out...
if exist out (
    rmdir /s /q out >nul 2>&1
)
mkdir out
mkdir out\net
mkdir out\net\foxsrv
mkdir out\net\foxsrv\xpb

echo.
echo ============================================
echo Verificando dependencias...
echo ============================================
echo.

REM Verificar Spigot API
if not exist spigot-api-1.20.1-R0.1-SNAPSHOT.jar (
    echo [ERRO] spigot-api-1.20.1-R0.1-SNAPSHOT.jar nao encontrado!
    echo Certifique-se de que o arquivo esta na pasta raiz.
    pause
    exit /b 1
) else (
    echo [OK] Spigot API encontrado
    set SPIGOT_PATH=spigot-api-1.20.1-R0.1-SNAPSHOT.jar
)

echo [INFO] Plugin XPBank nao requer Vault ou outras dependencias.
echo.

echo ============================================
echo Compilando XPBank...
echo ============================================
echo.

set CLASSPATH="%SPIGOT_PATH%"
echo Classpath: %CLASSPATH%
echo.

REM Verificar arquivo fonte
if not exist src\net\foxsrv\xpb\XPB.java (
    echo ============================================
    echo ERRO: Arquivo fonte nao encontrado!
    echo ============================================
    echo Caminho esperado: src\net\foxsrv\xpb\XPB.java
    pause
    exit /b 1
)

echo Compilando XPB.java...
%JAVAC% --release 17 -d out ^
-classpath %CLASSPATH% ^
-sourcepath src ^
src\net\foxsrv\xpb\XPB.java

if %errorlevel% neq 0 (
    echo ============================================
    echo ERRO AO COMPILAR O PLUGIN!
    echo ============================================
    pause
    exit /b 1
)

echo.
echo Compilacao concluida com sucesso!
echo.

echo ============================================
echo Copiando arquivos de recursos...
echo ============================================
echo.

REM Copiar plugin.yml
if exist resources\plugin.yml (
    copy resources\plugin.yml out\ >nul
    echo [OK] plugin.yml copiado
) else (
    echo [AVISO] plugin.yml nao encontrado. Criando padrao...
    (
        echo name: XPBank
        echo version: 1.1
        echo main: net.foxsrv.xpb.XPB
        echo api-version: 1.20
        echo author: FoxOficial2
        echo description: Safe store xp levels.
        echo website: https://bank.foxsrv.net
        echo.
        echo commands:
        echo   xpb:
        echo     description: Comando principal do XPBank
        echo     usage: /xpb ^<bal^|balance^|withdraw^|deposit^|pay^|admin^>
        echo     aliases: [xpbank]
        echo.
        echo permissions:
        echo   xpb.admin:
        echo     description: Permite comandos administrativos
        echo     default: op
        echo   xpb.use:
        echo     description: Permite usar comandos basicos
        echo     default: true
    ) > out\plugin.yml
)

REM Copiar config.yml (se existir, mas XPBank nao precisa)
if exist resources\config.yml (
    copy resources\config.yml out\ >nul
    echo [OK] config.yml copiado
) else (
    echo [INFO] Nenhum config.yml necessario para XPBank.
)

echo.
echo ============================================
echo Criando arquivo JAR...
echo ============================================
echo.

cd out
echo Criando XPBank.jar...
%JAR% cf XPBank.jar net plugin.yml 2>nul
if %errorlevel% neq 0 (
    %JAR% cf XPBank.jar net plugin.yml
)
cd ..

echo.
echo ============================================
echo PLUGIN COMPILADO COM SUCESSO!
echo ============================================
echo.
echo Arquivo gerado: out\XPBank.jar
echo.
dir out\XPBank.jar
echo.

echo ============================================
echo RESUMO DA COMPILACAO:
echo ============================================
echo.
echo - Data/Hora: %date% %time%
echo - Java Version: 17
echo - Pacote: net.foxsrv.xpb
echo - Main Class: net.foxsrv.xpb.XPB
echo - Dependencias: Spigot API 1.20+
echo.
echo ============================================
echo Para instalar:
echo ============================================
echo.
echo 1 - Copie out\XPBank.jar para a pasta plugins do servidor
echo 2 - Reinicie o servidor
echo 3 - Use /xpb help para comandos disponiveis
echo.
pause

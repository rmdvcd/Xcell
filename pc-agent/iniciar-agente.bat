@echo off
rem ============================================================
rem  DeviceScope - agente PC-Movil (Windows)
rem
rem  Doble clic para arrancar el agente que usa el modulo
rem  PC-Movil de la app. No compila nada: Python se ejecuta
rem  directamente. Crea un entorno virtual en .venv e instala
rem  las dependencias opcionales la primera vez.
rem
rem  Uso:
rem    iniciar-agente.bat                 arranca el agente
rem    iniciar-agente.bat /sin-extras     sin instalar paquetes opcionales
rem    iniciar-agente.bat --pin 123456    cualquier opcion de agent.py
rem    iniciar-agente.bat --devices       equipos ya emparejados
rem ============================================================
setlocal EnableExtensions EnableDelayedExpansion
title DeviceScope - agente PC-Movil
cd /d "%~dp0"

echo ==============================================================
echo   DeviceScope - agente PC-Movil  (Windows)
echo   Esta PC queda escuchando en tu red local: el telefono
echo   se conecta a ella con el PIN que aparece abajo.
echo ==============================================================
echo.

set "SKIP_EXTRAS="
set "AGENT_ARGS="

:parse
if "%~1"=="" goto :detect
if /i "%~1"=="/sin-extras" (
  set "SKIP_EXTRAS=1"
) else (
  set AGENT_ARGS=!AGENT_ARGS! "%~1"
)
shift
goto :parse

rem ------------------------------------------------------------
rem  1. Localizar un Python 3.9 o superior
rem ------------------------------------------------------------
:detect
set "BASE_PY="
call :try_py "py -3"
if not defined BASE_PY call :try_py "python"
if not defined BASE_PY call :try_py "python3"
if not defined BASE_PY (
  echo [ERROR] No se encontro Python 3.9 o superior.
  echo.
  echo   Opcion rapida, en PowerShell o cmd:
  echo       winget install -e --id Python.Python.3.12
  echo.
  echo   O descarga el instalador oficial:
  echo       https://www.python.org/downloads/windows/
  echo   Marca "Add python.exe to PATH" durante la instalacion
  echo   y vuelve a abrir esta ventana.
  echo.
  pause
  exit /b 1
)
echo [ok] Python: %BASE_PY%
%BASE_PY% -c "import sys; print('     version ' + sys.version.split()[0] + ' en ' + sys.executable)"
echo.

rem ------------------------------------------------------------
rem  2. Entorno virtual propio (deja intacto tu Python global)
rem ------------------------------------------------------------
set "RUN_PY=%BASE_PY%"
if not exist ".venv\Scripts\python.exe" (
  echo [..] Creando entorno virtual en .venv
  %BASE_PY% -m venv .venv >nul 2>&1
)
if exist ".venv\Scripts\python.exe" (
  set "RUN_PY=.venv\Scripts\python.exe"
) else (
  echo [aviso] No se pudo crear el entorno virtual: se usara el Python del sistema.
)
echo.

rem ------------------------------------------------------------
rem  3. Dependencias opcionales (una sola vez)
rem ------------------------------------------------------------
if defined SKIP_EXTRAS goto :port
if exist ".venv\.extras-instalados" goto :port
echo [..] Instalando dependencias opcionales: psutil, pillow, mss, pynput, sounddevice
echo      Necesita internet y puede tardar un par de minutos.
%RUN_PY% -m pip install --disable-pip-version-check --quiet --upgrade pip >nul 2>&1
%RUN_PY% -m pip install --disable-pip-version-check --quiet -r requirements.txt
if errorlevel 1 (
  echo [aviso] No se pudieron instalar todos los paquetes.
  echo         El agente arranca igual; cada herramienta que falte se anuncia
  echo         como no disponible, sin fallar en silencio.
  echo         Puedes reintentar con: %RUN_PY% -m pip install -r requirements.txt
) else (
  echo listo> ".venv\.extras-instalados"
)
echo.

rem ------------------------------------------------------------
rem  4. Aviso si el puerto de control ya esta ocupado
rem ------------------------------------------------------------
:port
netstat -ano -p tcp | findstr /i /c:":8787" | findstr /i "LISTENING" >nul 2>&1
if not errorlevel 1 (
  echo [aviso] El puerto 8787 ya esta en uso:
  netstat -ano -p tcp | findstr /i /c:":8787" | findstr /i "LISTENING"
  echo         Cierra la otra instancia del agente o arranca con otro puerto:
  echo             iniciar-agente.bat --port 8788
  echo.
)

rem ------------------------------------------------------------
rem  5. Diagnostico y arranque
rem ------------------------------------------------------------
echo [..] Comprobando que herramientas funcionan en esta PC
echo.
%RUN_PY% agent.py --diagnose

echo [i] Si el firewall de Windows pregunta, permite el acceso en
echo     "Redes privadas" para TCP 8787 y UDP 8788.
echo.
echo [..] Iniciando el agente. Pulsa Ctrl+C para detenerlo.
echo.
%RUN_PY% agent.py %AGENT_ARGS%
set "CODE=%ERRORLEVEL%"

echo.
if not "%CODE%"=="0" echo [fin] El agente termino con codigo %CODE%.
echo [fin] Agente detenido. Puedes cerrar esta ventana.
pause
exit /b %CODE%

rem ------------------------------------------------------------
rem  Subrutina: comprueba si un interprete es Python >= 3.9
rem ------------------------------------------------------------
:try_py
%~1 -c "import sys" >nul 2>&1
if errorlevel 1 exit /b 0
%~1 -c "import sys; sys.exit(0 if sys.version_info >= (3, 9) else 1)" >nul 2>&1
if errorlevel 1 exit /b 0
set "BASE_PY=%~1"
exit /b 0

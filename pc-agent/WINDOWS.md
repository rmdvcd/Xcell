# Arrancar el agente en Windows (10 y 11)

El agente de PC **no se compila**: es Python y se ejecuta directamente. Lo único
que hay que preparar es un intérprete de Python 3.9 o superior; el lanzador crea
un entorno virtual en `pc-agent/.venv` e instala las dependencias opcionales la
primera vez.

Lo que arranca aquí es el **lado PC del módulo PC-Móvil**. La app Android (Kotlin
+ Compose) se instala aparte en el teléfono; el teléfono y esta PC tienen que
estar en la **misma red** (mismo router, sin aislamiento de clientes).

## 1. Instalar Python (una sola vez)

En PowerShell o en `cmd`:

```powershell
winget install -e --id Python.Python.3.12
```

O desde <https://www.python.org/downloads/windows/> marcando **«Add python.exe to
PATH»** en la primera pantalla del instalador.

Comprueba que responde:

```powershell
py -3 --version
```

Debe imprimir `Python 3.9` o superior. Cierra y vuelve a abrir la terminal
después de instalar.

## 2. Arrancar el agente

Doble clic en:

```
pc-agent\iniciar-agente.bat
```

El lanzador, en orden:

1. Busca `py -3`, `python` o `python3` y verifica que sea ≥ 3.9.
2. Crea `pc-agent\.venv` y deja intacto tu Python global.
3. Instala `psutil`, `pillow`, `mss`, `pynput` y `sounddevice` (solo la primera
   vez; si no hay internet avisa y sigue).
4. Avisa si el puerto 8787 ya está ocupado.
5. Imprime el diagnóstico de capacidades y arranca el agente.

Verás algo así:

```
──────────────────────────────────────────────────────────────
 DeviceScope · agente PC-Móvil
──────────────────────────────────────────────────────────────
 Equipo      : DESKTOP-DEV
 Huella      : PC-04C540C1
 Dirección   : 192.168.1.42:8787
 PIN         : 794536   (cámbialo con --rotate-pin)
 Configuración: C:\Users\tu_usuario\.devicescope\agent.json
──────────────────────────────────────────────────────────────
```

Si el firewall de Windows pregunta, permite el acceso en **Redes privadas**
(TCP 8787 y UDP 8788). Deja la ventana abierta: mientras esté abierta, el
teléfono puede conectarse. `Ctrl+C` o cerrar la ventana lo detiene.

### Variantes

```bat
iniciar-agente.bat /sin-extras          rem sin instalar paquetes opcionales
iniciar-agente.bat --pin 123456         rem PIN fijo (demos)
iniciar-agente.bat --name "Mi PC"       rem nombre visible en el teléfono
iniciar-agente.bat --port 8788          rem otro puerto
iniciar-agente.bat --diagnose           rem solo comprobar capacidades
iniciar-agente.bat --devices            rem equipos ya emparejados
iniciar-agente.bat --rotate-pin         rem PIN nuevo
iniciar-agente.bat --revoke DS-XXXXXXXX rem expulsar un teléfono
iniciar-agente.bat --version
```

Cualquier otra opción de `agent.py` se pasa tal cual.

## 3. Emparejar el teléfono

En la app: **PC-Móvil → «Buscar en la red local»** → elige esta PC → escribe el
PIN de 6 dígitos. Si el barrido no la encuentra, conéctate por IP:
`192.168.x.x:8787`, la misma que imprime el agente en `Dirección`.

El teléfono guarda un token de 24 bytes en sus preferencias privadas; el agente
lo guarda en `%USERPROFILE%\.devicescope\agent.json`. Tras el primer
emparejamiento no hace falta repetir el PIN.

## 4. Qué habilita cada paquete

El agente arranca siempre; cada herramienta sin su paquete se anuncia como no
disponible en la app, sin fallar en silencio.

| Paquete | Habilita |
|---|---|
| `psutil` | CPU, RAM/swap, discos, red, procesos, batería y top de procesos |
| `pillow` + `mss` | espejo de pantalla y ventana con la cámara del móvil |
| `pynput` | teclado y mouse en ambos sentidos (Windows funciona sin extras) |
| `sounddevice` | micrófono del móvil hacia la PC y audio de la PC al teléfono |

`files`, `vpn` y `lan_scan` solo necesitan la biblioteca estándar.

## 5. Firewall y red

Windows suele preguntar la primera vez. Si lo bloqueaste por error, ejecuta esto
en una consola **como administrador**:

```bat
netsh advfirewall firewall add rule name="DeviceScope agente (TCP 8787)" dir=in action=allow protocol=TCP localport=8787 profile=private
netsh advfirewall firewall add rule name="DeviceScope descubrimiento (UDP 8788)" dir=in action=allow protocol=UDP localport=8788 profile=private
```

Para quitarlas:

```bat
netsh advfirewall firewall delete rule name="DeviceScope agente (TCP 8787)"
netsh advfirewall firewall delete rule name="DeviceScope descubrimiento (UDP 8788)"
```

Notas:

* Las reglas son solo para el perfil **privado**; en redes públicas el agente
  queda inalcanzable, que es lo deseable.
* Red de invitados o «aislamiento de clientes» del router impide el
  descubrimiento y la conexión: usa la red normal.
* El enlace **no va cifrado**: es una LAN de confianza. No lo uses en redes
  ajenas.

## 6. Problemas frecuentes

| Síntoma | Causa y solución |
|---|---|
| «No se encontró Python 3.9 o superior» | Falta Python o no está en el PATH. Reinstala marcando *Add python.exe to PATH* y abre una ventana nueva. |
| `python` abre la Microsoft Store | Es el alias falso de Windows. Usa `py -3`, o desactiva *App execution aliases → python.exe* en Configuración. |
| `[Errno 10048]` / «puerto en uso» | Ya hay un agente arrancado. Cierra la otra ventana o usa `--port 8788` (y ese puerto en la app). |
| El teléfono no descubre la PC | Firewall, red de invitados, o PC y teléfono en subredes distintas. Conéctate por IP:8787. |
| «Requiere permiso» o herramienta deshabilitada | Falta el paquete opcional o el permiso en el teléfono; mira el diagnóstico de capacidades del arranque. |
| El audio o la cámara no aparecen | `sounddevice` / `pillow`+`mss` no se instalaron (sin internet). Reintenta: `.venv\Scripts\python -m pip install -r requirements.txt`. |

Para comprobar la instalación sin arrancar el servicio:

```bat
cd pc-agent
.venv\Scripts\python agent.py --diagnose
```

## 7. Detalles de estado

* Configuración y tokens: `%USERPROFILE%\.devicescope\agent.json` (permisos
  restringidos al usuario). Bórralo para olvidar todos los emparejamientos.
* Subidas desde el teléfono: `%USERPROFILE%\DeviceScope\uploads`.
* El agente no abre conexiones salientes salvo las que provoca la VPN o una
  descarga de archivos. No hay telemetría, ni cuentas, ni nube.

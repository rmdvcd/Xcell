"""Métricas de la PC y barrido de la subred local.

Usa ``psutil`` cuando está instalado y cae a ``/proc`` + ``shutil`` cuando no.
El agente nunca falla por una métrica ausente: omite el campo y la app muestra
«—».
"""

from __future__ import annotations

import ipaddress
import os
import platform
import re
import shutil
import socket
import subprocess
import sys
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, field
from typing import Optional

try:  # pragma: no cover - dependencia opcional
    import psutil
except Exception:  # noqa: BLE001 - cualquier error de importación degrada
    psutil = None


def _read(path: str) -> Optional[str]:
    try:
        with open(path, "r", encoding="utf-8", errors="replace") as handle:
            return handle.read()
    except OSError:
        return None


def _cpu_name() -> str:
    system = platform.system()
    if system == "Linux":
        text = _read("/proc/cpuinfo") or ""
        match = re.search(r"model name\s*:\s*(.+)", text)
        if match:
            return match.group(1).strip()
    if system == "Darwin":
        return platform.processor() or "Apple Silicon"
    if system == "Windows":
        name = os.environ.get("PROCESSOR_IDENTIFIER")
        if name:
            return name.strip()
    return platform.processor() or platform.machine()


def _freq_mhz() -> float:
    if psutil is not None:
        try:
            freq = psutil.cpu_freq()
            if freq and freq.current:
                return float(freq.current)
        except Exception:  # noqa: BLE001
            pass
    if platform.system() == "Linux":
        text = _read("/sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq")
        if text:
            try:
                return float(text.strip()) / 1000.0
            except ValueError:
                pass
    return 0.0


def _temperature() -> Optional[float]:
    if psutil is not None:
        try:
            temps = psutil.sensors_temperatures()
            for entries in temps.values():
                for entry in entries:
                    if entry.current:
                        return round(float(entry.current), 1)
        except Exception:  # noqa: BLE001
            pass
    raw = _read("/sys/class/thermal/thermal_zone0/temp")
    if raw:
        try:
            value = float(raw.strip())
            return round(value / 1000.0 if value > 200 else value, 1)
        except ValueError:
            pass
    return None


def _gpu() -> tuple[Optional[float], str, Optional[float]]:
    """Uso, nombre y temperatura de GPU NVIDIA si ``nvidia-smi`` existe."""

    if shutil.which("nvidia-smi") is None:
        return None, "", None
    try:
        output = subprocess.run(
            [
                "nvidia-smi",
                "--query-gpu=utilization.gpu,name,temperature.gpu",
                "--format=csv,noheader,nounits",
            ],
            capture_output=True,
            text=True,
            timeout=2,
        )
        if output.returncode != 0 or not output.stdout.strip():
            return None, "", None
        first = output.stdout.strip().splitlines()[0]
        parts = [part.strip() for part in first.split(",")]
        usage = float(parts[0]) if parts[0] else None
        name = parts[1] if len(parts) > 1 else ""
        temp = float(parts[2]) if len(parts) > 2 and parts[2] else None
        return usage, name, temp
    except (OSError, ValueError, subprocess.SubprocessError):
        return None, "", None


def _battery() -> tuple[Optional[int], Optional[bool]]:
    if psutil is None:
        return None, None
    try:
        battery = psutil.sensors_battery()
    except Exception:  # noqa: BLE001
        return None, None
    if battery is None:
        return None, None
    return int(round(battery.percent)), bool(battery.power_plugged)


def _disks() -> list[dict]:
    disks: list[dict] = []
    if psutil is not None:
        try:
            for part in psutil.disk_partitions(all=False):
                try:
                    usage = psutil.disk_usage(part.mountpoint)
                except (PermissionError, OSError):
                    continue
                disks.append(
                    {
                        "mount": part.mountpoint,
                        "used": usage.used,
                        "total": usage.total,
                    }
                )
            return disks[:4]
        except Exception:  # noqa: BLE001
            pass
    root = "C:\\" if platform.system() == "Windows" else "/"
    try:
        usage = shutil.disk_usage(root)
        disks.append({"mount": root, "used": usage.used, "total": usage.total})
    except OSError:
        pass
    return disks


def _uptime() -> float:
    if psutil is not None:
        try:
            return time.time() - psutil.boot_time()
        except Exception:  # noqa: BLE001
            pass
    raw = _read("/proc/uptime")
    if raw:
        try:
            return float(raw.split()[0])
        except (ValueError, IndexError):
            pass
    return 0.0


def _top_processes(limit: int = 6) -> tuple[list[dict], int, int]:
    processes: list[dict] = []
    count = 0
    threads = 0
    if psutil is None:
        return processes, count, threads
    try:
        for proc in psutil.process_iter(["pid", "name", "cpu_percent", "memory_info", "num_threads"]):
            try:
                info = proc.info
                count += 1
                threads += int(info.get("num_threads") or 0)
                memory = info.get("memory_info")
                processes.append(
                    {
                        "pid": info.get("pid", 0),
                        "name": (info.get("name") or "?")[:32],
                        "cpu": float(info.get("cpu_percent") or 0.0),
                        "ram": int(getattr(memory, "rss", 0) or 0),
                    }
                )
            except (psutil.NoSuchProcess, psutil.AccessDenied):
                continue
    except Exception:  # noqa: BLE001
        pass
    processes.sort(key=lambda item: item["cpu"], reverse=True)
    return processes[:limit], count, threads


@dataclass
class NetworkCounters:
    received: int = 0
    sent: int = 0
    timestamp: float = field(default_factory=time.time)


class MetricsSampler:
    """Muestreo de recursos; calcula deltas de CPU y red entre llamadas."""

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._net = NetworkCounters()
        self._cpu_primed = False
        if psutil is not None:
            try:
                psutil.cpu_percent(interval=None)
                self._cpu_primed = True
            except Exception:  # noqa: BLE001
                pass

    def _net_counters(self) -> NetworkCounters:
        if psutil is not None:
            try:
                counters = psutil.net_io_counters()
                return NetworkCounters(int(counters.bytes_recv), int(counters.bytes_sent))
            except Exception:  # noqa: BLE001
                pass
        if platform.system() == "Linux":
            text = _read("/proc/net/dev") or ""
            received = sent = 0
            for line in text.splitlines()[2:]:
                parts = line.split()
                if len(parts) < 10:
                    continue
                name = parts[0].rstrip(":")
                if name == "lo":
                    continue
                received += int(parts[1])
                sent += int(parts[9])
            return NetworkCounters(received, sent)
        return NetworkCounters()

    def _cpu_percent(self) -> float:
        if psutil is not None and self._cpu_primed:
            try:
                return float(psutil.cpu_percent(interval=None))
            except Exception:  # noqa: BLE001
                pass
        if platform.system() == "Linux":
            stat = _read("/proc/stat")
            if stat:
                parts = stat.splitlines()[0].split()[1:]
                values = [int(value) for value in parts[:8]]
                idle = values[3] + values[4]
                total = sum(values)
                with self._lock:
                    previous = getattr(self, "_prev_cpu", None)
                    self._prev_cpu = (total, idle)
                if previous:
                    total_delta = total - previous[0]
                    idle_delta = idle - previous[1]
                    if total_delta > 0:
                        return max(0.0, min(100.0, 100.0 * (total_delta - idle_delta) / total_delta))
        return 0.0

    def _memory(self) -> tuple[int, int, int, int]:
        if psutil is not None:
            try:
                memory = psutil.virtual_memory()
                swap = psutil.swap_memory()
                return (
                    int(memory.total - memory.available),
                    int(memory.total),
                    int(swap.used),
                    int(swap.total),
                )
            except Exception:  # noqa: BLE001
                pass
        text = _read("/proc/meminfo") or ""
        values = {}
        for line in text.splitlines():
            parts = line.split(":")
            if len(parts) == 2:
                key = parts[0].strip()
                number = parts[1].strip().split()
                if number:
                    try:
                        values[key] = int(number[0]) * 1024
                    except ValueError:
                        continue
        total = values.get("MemTotal", 0)
        available = values.get("MemAvailable", values.get("MemFree", 0))
        swap_total = values.get("SwapTotal", 0)
        swap_free = values.get("SwapFree", 0)
        return max(0, total - available), total, max(0, swap_total - swap_free), swap_total

    def snapshot(self) -> dict:
        usage, gpu_name, gpu_temp = _gpu()
        counters = self._net_counters()
        with self._lock:
            elapsed = max(0.001, counters.timestamp - self._net.timestamp)
            rx_rate = max(0.0, (counters.received - self._net.received) / elapsed)
            tx_rate = max(0.0, (counters.sent - self._net.sent) / elapsed)
            self._net = counters
        ram_used, ram_total, swap_used, swap_total = self._memory()
        processes, process_count, thread_count = _top_processes()
        battery, charging = _battery()
        load = None
        try:
            load = round(os.getloadavg()[0], 2)
        except (AttributeError, OSError):
            pass

        interface = ""
        ip_address = ""
        if psutil is not None:
            try:
                stats = psutil.net_if_stats()
                for name, stat in stats.items():
                    if stat.isup and not name.startswith("lo"):
                        interface = name
                        break
            except Exception:  # noqa: BLE001
                pass
        if not ip_address:
            try:
                from .state import local_ip

                ip_address = local_ip()
            except Exception:  # noqa: BLE001
                ip_address = ""

        return {
            "ts": int(time.time() * 1000),
            "cpu": round(self._cpu_percent(), 1),
            "cpu_name": _cpu_name(),
            "cores": os.cpu_count() or 1,
            "freq": round(_freq_mhz(), 0),
            "cpu_temp": _temperature(),
            "gpu": usage,
            "gpu_name": gpu_name,
            "gpu_temp": gpu_temp,
            "ram_used": ram_used,
            "ram_total": ram_total,
            "swap_used": swap_used,
            "swap_total": swap_total,
            "disks": _disks(),
            "net_rx": round(rx_rate, 1),
            "net_tx": round(tx_rate, 1),
            "net_if": interface,
            "net_ip": ip_address,
            "wifi": _wifi_ssid(),
            "battery": battery,
            "charging": charging,
            "uptime": int(_uptime()),
            "processes": process_count,
            "threads": thread_count,
            "load": load,
            "host": platform.node() or "PC",
            "os": _os_name(),
            "top": processes,
        }


def _wifi_ssid() -> str:
    system = platform.system()
    try:
        if system == "Linux":
            output = subprocess.run(
                ["iwgetid", "-r"], capture_output=True, text=True, timeout=1
            )
            return output.stdout.strip()
        if system == "Darwin":
            output = subprocess.run(
                ["/System/Library/PrivateFrameworks/Apple80211.framework/Versions/Current/Resources/airport", "-I"],
                capture_output=True,
                text=True,
                timeout=1,
            )
            for line in output.stdout.splitlines():
                if " SSID:" in line:
                    return line.split("SSID:")[-1].strip()
        if system == "Windows":
            output = subprocess.run(
                ["netsh", "wlan", "show", "interfaces"],
                capture_output=True,
                text=True,
                timeout=2,
                encoding="utf-8",
                errors="replace",
            )
            for line in output.stdout.splitlines():
                if "SSID" in line and "BSSID" not in line:
                    return line.split(":", 1)[-1].strip()
    except (OSError, subprocess.SubprocessError):
        pass
    return ""


def _os_name() -> str:
    system = platform.system()
    if system == "Windows":
        return f"Windows {platform.release()}"
    if system == "Darwin":
        return f"macOS {platform.mac_ver()[0]}"
    try:
        text = _read("/etc/os-release") or ""
        for line in text.splitlines():
            if line.startswith("PRETTY_NAME="):
                return line.split("=", 1)[1].strip().strip('"')
    except OSError:
        pass
    return f"{system} {platform.release()}"


# ---------------------------------------------------------------- LAN scan

COMMON_PORTS = [22, 53, 80, 139, 443, 445, 515, 631, 3000, 3389, 5000, 8006, 8080, 8443, 9100]


def _local_networks() -> list[ipaddress.IPv4Network]:
    networks: list[ipaddress.IPv4Network] = []
    try:
        from .state import local_ip

        address = local_ip()
        if address:
            networks.append(ipaddress.IPv4Network(f"{address}/24", strict=False))
    except Exception:  # noqa: BLE001
        pass
    if psutil is not None:
        try:
            for _, addresses in psutil.net_if_addrs().items():
                for addr in addresses:
                    if addr.family == socket.AF_INET and not addr.address.startswith("127."):
                        try:
                            networks.append(
                                ipaddress.IPv4Network(f"{addr.address}/{addr.netmask}", strict=False)
                            )
                        except (ValueError, TypeError):
                            continue
        except Exception:  # noqa: BLE001
            pass
    unique: dict[str, ipaddress.IPv4Network] = {}
    for network in networks:
        if network.num_addresses <= 4096:
            unique[str(network)] = network
    return list(unique.values())


def _arp_table() -> dict[str, str]:
    table: dict[str, str] = {}
    if platform.system() == "Linux":
        text = _read("/proc/net/arp") or ""
        for line in text.splitlines()[1:]:
            parts = line.split()
            if len(parts) >= 4 and parts[3] != "00:00:00:00:00:00":
                table[parts[0]] = parts[3].lower()
    elif platform.system() == "Windows":
        try:
            output = subprocess.run(["arp", "-a"], capture_output=True, text=True, timeout=3)
            for line in output.stdout.splitlines():
                match = re.search(r"(\d+\.\d+\.\d+\.\d+)\s+([0-9a-fA-F-]{17})", line)
                if match:
                    table[match.group(1)] = match.group(2).replace("-", ":").lower()
        except (OSError, subprocess.SubprocessError):
            pass
    return table


def _probe(ip: str, ports: list[int], timeout: float = 0.25) -> list[int]:
    opened: list[int] = []
    for port in ports:
        sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        sock.settimeout(timeout)
        try:
            if sock.connect_ex((ip, port)) == 0:
                opened.append(port)
        except OSError:
            continue
        finally:
            sock.close()
    return opened


def scan_lan(deep: bool = False, progress=None) -> list[dict]:
    """Barrido de la subred: ARP + puertos comunes por host activo.

    No requiere root. ``deep`` explora más puertos y también el nombre inverso.
    """

    networks = _local_networks()
    arp = _arp_table()
    candidates: list[str] = []
    for network in networks:
        if network.num_addresses > 1024 and not deep:
            # Redes grandes: solo los vecinos ya vistos en la tabla ARP.
            candidates.extend(ip for ip in arp if ipaddress.ip_address(ip) in network)
        else:
            candidates.extend(str(host) for host in network.hosts())
    candidates = sorted(set(candidates))
    ports = COMMON_PORTS + ([21, 23, 25, 110, 143, 587, 993, 3306, 5432, 6379, 27017] if deep else [])
    results: list[dict] = []
    completed = 0
    total = max(1, len(candidates))
    lock = threading.Lock()

    def probe_host(ip: str) -> Optional[dict]:
        nonlocal completed
        opened = _probe(ip, ports)
        with lock:
            completed += 1
            if progress and completed % 8 == 0:
                progress(int(100 * completed / total))
        if not opened:
            return None
        name = ""
        try:
            name = socket.gethostbyaddr(ip)[0]
        except OSError:
            pass
        return {
            "ip": ip,
            "name": name,
            "mac": arp.get(ip, ""),
            "ports": opened,
        }

    with ThreadPoolExecutor(max_workers=48) as pool:
        for result in pool.map(probe_host, candidates):
            if result:
                results.append(result)
    results.sort(key=lambda item: ipaddress.ip_address(item["ip"]))
    return results

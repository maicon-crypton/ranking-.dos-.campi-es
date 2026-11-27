"""Consulta informativa da fila de impressão do Windows, quando disponível."""

from __future__ import annotations

import logging
import sys
import time
from collections.abc import Callable


LOGGER = logging.getLogger(__name__)


def watch_print_jobs(
    printer_name: str,
    on_job: Callable[[str], None],
    stop_requested: Callable[[], bool],
    poll_interval: float = 2.0,
) -> None:
    """Notifica sobre novos títulos de trabalhos; não lê conteúdo nem pausa impressão."""
    if sys.platform != "win32":
        raise OSError("A consulta do spooler só está disponível no Windows.")
    try:
        import win32print
    except ImportError as error:
        raise RuntimeError("Instale pywin32 para consultar a fila de impressão.") from error

    seen: set[tuple[str, int]] = set()
    while not stop_requested():
        handle = None
        try:
            handle = win32print.OpenPrinter(printer_name)
            jobs = win32print.EnumJobs(handle, 0, 999, 1)
            active = set()
            for job in jobs:
                key = (str(job.get("pDocument", "")), int(job.get("JobId", 0)))
                active.add(key)
                if key not in seen:
                    on_job(key[0])
            seen = active
        except Exception:
            LOGGER.exception("Não foi possível consultar a impressora %r.", printer_name)
            raise
        finally:
            if handle is not None:
                win32print.ClosePrinter(handle)
        time.sleep(poll_interval)

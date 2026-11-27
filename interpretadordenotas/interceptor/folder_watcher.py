"""Observa novos arquivos exportados sem interceptar teclas ou bloquear impressão."""

from __future__ import annotations

import logging
import threading
from pathlib import Path
from typing import Callable


LOGGER = logging.getLogger(__name__)
SUPPORTED_SUFFIXES = {".txt", ".pdf"}


class FolderWatcher:
    """Emite arquivos estáveis criados após o início do monitor."""

    def __init__(
        self,
        folder: Path,
        on_file: Callable[[Path], None],
        poll_interval: float = 2.0,
    ) -> None:
        self.folder = folder
        self.on_file = on_file
        self.poll_interval = poll_interval
        self._stop = threading.Event()
        self._thread: threading.Thread | None = None

    def start(self) -> None:
        """Ignora documentos preexistentes e inicia a observação em segundo plano."""
        self.folder.mkdir(parents=True, exist_ok=True)
        self._stop.clear()
        self._thread = threading.Thread(target=self._run, name="santri-folder-watcher", daemon=True)
        self._thread.start()

    def stop(self) -> None:
        """Solicita parada e espera o ciclo atual terminar."""
        self._stop.set()
        if self._thread and self._thread.is_alive():
            self._thread.join(timeout=max(2.0, self.poll_interval + 1.0))

    def _run(self) -> None:
        """Entrega somente arquivos novos que permaneceram estáveis por dois ciclos."""
        known: set[Path] = set(self.folder.glob("*"))
        observed: dict[Path, tuple[int, int]] = {}
        while not self._stop.wait(self.poll_interval):
            for path in self.folder.iterdir():
                if path.suffix.casefold() not in SUPPORTED_SUFFIXES or not path.is_file():
                    continue
                if path in known:
                    continue
                try:
                    stat = path.stat()
                except OSError:
                    continue
                current = (stat.st_size, stat.st_mtime_ns)
                previous = observed.get(path)
                if previous == current:
                    known.add(path)
                    observed.pop(path, None)
                    try:
                        self.on_file(path)
                    except Exception:
                        LOGGER.exception("Falha ao encaminhar arquivo estável: %s", path.name)
                else:
                    observed[path] = current

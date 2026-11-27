"""Ponto de entrada da interface desktop e do monitor de arquivos."""

from __future__ import annotations

import argparse
import logging
import queue
import sys
import tkinter as tk
from pathlib import Path
from tkinter import messagebox

from config.settings import load_settings
from interceptor.folder_watcher import FolderWatcher
from parser.santri_note_parser import NoteParseError, read_note_file
from scoring.score_calculator import calculate_score
from sync.credit_dispatcher import CreditDispatcher, DuplicateDocumentError
from ui.auxiliary_prompt_modal import AuxiliaryPromptModal


LOGGER = logging.getLogger("interpretadordenotas")


class DesktopApp:
    """Conecta o watcher, a janela de identificação e a fila local."""

    def __init__(self, root: tk.Tk, config_path: Path) -> None:
        self.root = root
        self.settings = load_settings(config_path)
        self.events: queue.Queue[Path] = queue.Queue()
        self.dispatcher = CreditDispatcher(self.settings.database_path)
        self.watcher = FolderWatcher(
            self.settings.watch_folder,
            self.events.put,
            poll_interval=self.settings.poll_interval_seconds,
        )
        self.root.title("SantriRank — Interpretador de Notas")
        self.root.geometry("380x120")
        self.root.resizable(False, False)
        tk.Label(
            root,
            text=f"Monitorando notas em:\n{self.settings.watch_folder}",
            justify="left",
            wraplength=350,
        ).pack(padx=16, pady=(18, 8), anchor="w")
        tk.Button(root, text="Exportar lançamentos pendentes", command=self.export_pending).pack(
            padx=16, pady=(0, 12), anchor="w"
        )
        self.root.protocol("WM_DELETE_WINDOW", self.close)
        self.watcher.start()
        self.root.after(250, self.process_events)

    def process_events(self) -> None:
        while True:
            try:
                path = self.events.get_nowait()
            except queue.Empty:
                break
            self.handle_file(path)
        self.root.after(250, self.process_events)

    def handle_file(self, path: Path) -> None:
        try:
            note = read_note_file(path)
            score = calculate_score(note, self.settings.weights)
        except (NoteParseError, OSError, ValueError) as error:
            LOGGER.warning("Arquivo recusado (%s): %s", path.name, error)
            messagebox.showwarning("Nota não interpretada", f"{path.name}\n\n{error}", parent=self.root)
            return

        modal = AuxiliaryPromptModal(self.root, note, score, self.settings.employees)
        if modal.result is None:
            try:
                self.dispatcher.defer(note, str(path))
            except DuplicateDocumentError:
                self.show_duplicate(note.order_number)
            else:
                messagebox.showinfo(
                    "Nota adiada",
                    "Documento guardado sem identificação para revisão. Nenhum ponto foi creditado.",
                    parent=self.root,
                )
            return

        try:
            self.dispatcher.queue_credit(
                note=note,
                points=score.total,
                employee_code=modal.result.employee_code,
                observation=modal.result.observation,
                source=str(path),
            )
        except DuplicateDocumentError:
            self.show_duplicate(note.order_number)
            return
        messagebox.showinfo(
            "Lançamento pendente",
            f"Pedido {note.order_number} foi associado a {modal.result.employee_code} "
            f"({score.total:g} pontos) na fila local.\n\n"
            "O Android ainda não está conectado a um serviço de sincronização.",
            parent=self.root,
        )

    def show_duplicate(self, order_number: str) -> None:
        messagebox.showwarning(
            "Nota duplicada",
            f"O pedido {order_number} já foi registrado. A tentativa foi anotada localmente.",
            parent=self.root,
        )

    def export_pending(self) -> None:
        try:
            destination = self.dispatcher.export_pending(self.settings.export_folder)
        except OSError as error:
            LOGGER.exception("Falha ao exportar fila local.")
            messagebox.showerror("Falha na exportação", str(error), parent=self.root)
            return
        messagebox.showinfo("Exportação concluída", f"Arquivo criado:\n{destination}", parent=self.root)

    def close(self) -> None:
        self.watcher.stop()
        self.dispatcher.close()
        self.root.destroy()


def main() -> int:
    """Carrega a configuração e inicia a interface Tk."""
    parser = argparse.ArgumentParser(description="Interpretador local de notas do Santri.")
    parser.add_argument(
        "--config",
        type=Path,
        default=Path(__file__).parent / "config" / "santri_config.json",
        help="Caminho para o arquivo JSON de configuração.",
    )
    args = parser.parse_args()
    try:
        settings = load_settings(args.config)
    except (OSError, ValueError) as error:
        print(f"Configuração inválida: {error}", file=sys.stderr)
        return 2
    settings.watch_folder.mkdir(parents=True, exist_ok=True)
    settings.database_path.parent.mkdir(parents=True, exist_ok=True)
    settings.export_folder.mkdir(parents=True, exist_ok=True)
    settings.log_path.parent.mkdir(parents=True, exist_ok=True)

    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)s %(name)s: %(message)s",
        handlers=[
            logging.StreamHandler(),
            logging.FileHandler(settings.log_path, encoding="utf-8"),
        ],
    )
    root = tk.Tk()
    DesktopApp(root, args.config)
    root.mainloop()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

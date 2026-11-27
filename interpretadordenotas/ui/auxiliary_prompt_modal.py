"""Janela compacta para vincular uma nota à matrícula do auxiliar."""

from __future__ import annotations

import tkinter as tk
from dataclasses import dataclass
from tkinter import messagebox, ttk

from config.settings import Employee
from parser.santri_note_parser import ParsedNote
from scoring.score_calculator import ScoreBreakdown


@dataclass(frozen=True)
class AuxiliarySelection:
    employee_code: str
    observation: str


class AuxiliaryPromptModal:
    """Modal com confirmação por Enter e adiamento explícito por Escape/fechar."""

    def __init__(
        self,
        parent: tk.Misc,
        note: ParsedNote,
        score: ScoreBreakdown,
        employees: tuple[Employee, ...],
    ) -> None:
        self.result: AuxiliarySelection | None = None
        window = tk.Toplevel(parent)
        window.title("Identificar auxiliar")
        window.geometry("410x300")
        window.resizable(False, False)
        window.transient(parent)
        window.grab_set()
        window.focus_force()
        window.protocol("WM_DELETE_WINDOW", window.destroy)

        summary = (
            f"Pedido {note.order_number}\n"
            f"{len(note.items)} item(ns) • {score.unit_count:g} PC/UN • "
            f"{score.linear_meters:g} MT\n"
            f"Pontuação calculada: {score.total:g} pontos"
        )
        ttk.Label(window, text=summary, justify="left", wraplength=370).pack(
            padx=18, pady=(20, 16), anchor="w"
        )
        ttk.Label(window, text="Matrícula do auxiliar").pack(padx=18, anchor="w")
        self.employee_var = tk.StringVar()
        choices = [f"{item.code} — {item.name}" for item in employees]
        self.employee_lookup = {choice: item.code for choice, item in zip(choices, employees)}
        self.employee_box = ttk.Combobox(
            window,
            textvariable=self.employee_var,
            values=choices,
            state="readonly" if choices else "normal",
        )
        self.employee_box.pack(fill="x", padx=18, pady=(4, 12))
        ttk.Label(window, text="Observação (opcional)").pack(padx=18, anchor="w")
        self.observation = tk.StringVar()
        ttk.Entry(window, textvariable=self.observation).pack(
            fill="x", padx=18, pady=(4, 16)
        )
        actions = ttk.Frame(window)
        actions.pack(fill="x", padx=18)
        ttk.Button(actions, text="Adiar", command=window.destroy).pack(side="left")
        ttk.Button(actions, text="Confirmar", command=lambda: self._confirm(window)).pack(side="right")
        window.bind("<Return>", lambda _event: self._confirm(window))
        window.bind("<Escape>", lambda _event: window.destroy())
        self.employee_box.focus_set()
        window.wait_window()

    def _confirm(self, window: tk.Toplevel) -> None:
        """Valida a matrícula e fecha o modal somente após entrada válida."""
        selected = self.employee_var.get().strip()
        employee_code = self.employee_lookup.get(selected, selected)
        if self.employee_lookup and selected not in self.employee_lookup:
            messagebox.showwarning(
                "Auxiliar não cadastrado",
                "Selecione uma matrícula da lista configurada.",
                parent=window,
            )
            self.employee_box.focus_set()
            return
        if not employee_code:
            self.employee_box.focus_set()
            return
        self.result = AuxiliarySelection(employee_code, self.observation.get().strip())
        window.destroy()

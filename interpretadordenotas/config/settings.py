"""Leitura e validação da configuração local do interpretador."""

from __future__ import annotations

import json
import math
import os
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from scoring.score_calculator import ScoringWeights


@dataclass(frozen=True)
class Employee:
    """Identificação rápida de um colaborador configurado localmente."""

    code: str
    name: str


@dataclass(frozen=True)
class Settings:
    """Configuração validada da pasta, fila e pontuação."""

    watch_folder: Path
    export_folder: Path
    database_path: Path
    log_path: Path
    poll_interval_seconds: float
    employees: tuple[Employee, ...]
    weights: ScoringWeights


def _number(data: dict[str, Any], key: str, default: float) -> float:
    value = data.get(key, default)
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise ValueError(f"O peso {key!r} deve ser numérico.")
    number = float(value)
    if not math.isfinite(number) or number < 0:
        raise ValueError(f"O peso {key!r} deve ser finito e não negativo.")
    return number


def load_settings(config_path: Path) -> Settings:
    """Carrega JSON sem expor credenciais e valida valores de operação."""
    raw = json.loads(config_path.read_text(encoding="utf-8"))
    if not isinstance(raw, dict):
        raise ValueError("A configuração deve ser um objeto JSON.")
    app_data = Path(os.environ.get("LOCALAPPDATA", Path.home())) / "SantriRank"
    paths = raw.get("paths", {})
    if not isinstance(paths, dict):
        raise ValueError("'paths' deve ser um objeto.")

    def resolve(value: str | None, fallback: Path) -> Path:
        candidate = Path(value).expanduser() if value else fallback
        expanded = os.path.expandvars(str(candidate))
        return Path(expanded).resolve()

    weights_data = raw.get("weights", {})
    if not isinstance(weights_data, dict):
        raise ValueError("'weights' deve ser um objeto.")
    weights = ScoringWeights(**{
        field: _number(weights_data, field, default)
        for field, default in ScoringWeights().as_dict().items()
    })
    polling = raw.get("poll_interval_seconds", 2.0)
    if (
        isinstance(polling, bool)
        or not isinstance(polling, (int, float))
        or not math.isfinite(float(polling))
        or not 0.25 <= polling <= 60
    ):
        raise ValueError("'poll_interval_seconds' deve estar entre 0,25 e 60.")

    employees_raw = raw.get("employees", [])
    if not isinstance(employees_raw, list):
        raise ValueError("'employees' deve ser uma lista.")
    employees: list[Employee] = []
    seen_codes: set[str] = set()
    for employee in employees_raw:
        if not isinstance(employee, dict):
            raise ValueError("Cada colaborador deve ser um objeto.")
        code = str(employee.get("code", "")).strip()
        name = str(employee.get("name", "")).strip()
        if not code or not name or code.casefold() in seen_codes:
            raise ValueError("Cada colaborador precisa de código e nome únicos.")
        seen_codes.add(code.casefold())
        employees.append(Employee(code=code, name=name))

    return Settings(
        watch_folder=resolve(paths.get("watch_folder"), Path.home() / "Santri" / "Exportacoes"),
        export_folder=resolve(paths.get("export_folder"), app_data / "exportacoes"),
        database_path=resolve(paths.get("database_path"), app_data / "interpretador.sqlite3"),
        log_path=resolve(paths.get("log_path"), app_data / "interpretador.log"),
        poll_interval_seconds=float(polling),
        employees=tuple(employees),
        weights=weights,
    )

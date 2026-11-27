"""Fila SQLite idempotente; sem endpoint configurado, nada é marcado como creditado."""

from __future__ import annotations

import json
import math
import sqlite3
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from parser.santri_note_parser import ParsedNote


class DuplicateDocumentError(ValueError):
    """O documento ou pedido já está na fila ou foi processado localmente."""


class CreditDispatcher:
    """Persiste lançamentos locais e exporta apenas para revisão/sincronização futura."""

    def __init__(self, database_path: Path) -> None:
        database_path.parent.mkdir(parents=True, exist_ok=True)
        self.connection = sqlite3.connect(database_path, timeout=10)
        self.connection.row_factory = sqlite3.Row
        self.connection.execute("PRAGMA foreign_keys = ON")
        self.connection.executescript(
            """
            CREATE TABLE IF NOT EXISTS credit_queue (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                order_number TEXT NOT NULL UNIQUE,
                document_hash_sha256 TEXT NOT NULL UNIQUE,
                employee_code TEXT,
                points REAL NOT NULL,
                observation TEXT NOT NULL DEFAULT '',
                source TEXT NOT NULL,
                status TEXT NOT NULL CHECK(status IN ('pending', 'needs_identification')),
                note_json TEXT NOT NULL,
                created_at TEXT NOT NULL
            );
            CREATE TABLE IF NOT EXISTS duplicate_attempts (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                order_number TEXT NOT NULL,
                document_hash_sha256 TEXT NOT NULL,
                attempted_employee_code TEXT NOT NULL DEFAULT '',
                attempted_at TEXT NOT NULL
            );
            """
        )

    def queue_credit(
        self,
        note: ParsedNote,
        points: float,
        employee_code: str,
        observation: str = "",
        source: str = "",
    ) -> int:
        """Registra uma nota identificada sem afirmar que foi enviada ao Android."""
        employee = employee_code.strip()
        if not employee:
            raise ValueError("Informe a matrícula do auxiliar.")
        if not math.isfinite(points) or points < 0:
            raise ValueError("A pontuação precisa ser finita e não negativa.")
        return self._insert(note, points, employee, observation.strip(), source, "pending")

    def defer(self, note: ParsedNote, source: str = "") -> int:
        """Guarda uma nota adiada sem matrícula para revisão; não calcula crédito."""
        return self._insert(note, 0.0, "", "", source, "needs_identification")

    def _insert(
        self,
        note: ParsedNote,
        points: float,
        employee_code: str,
        observation: str,
        source: str,
        status: str,
    ) -> int:
        """Aplica anti-duplicidade por hash e pedido dentro de uma transação."""
        created_at = datetime.now(timezone.utc).isoformat()
        try:
            cursor = self.connection.execute(
                """
                INSERT INTO credit_queue (
                    order_number, document_hash_sha256, employee_code, points,
                    observation, source, status, note_json, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    note.order_number,
                    note.document_hash_sha256,
                    employee_code,
                    points,
                    observation,
                    source,
                    status,
                    json.dumps(note.to_dict(), ensure_ascii=False),
                    created_at,
                ),
            )
            self.connection.commit()
            return int(cursor.lastrowid)
        except sqlite3.IntegrityError as error:
            self.connection.rollback()
            self.connection.execute(
                """
                INSERT INTO duplicate_attempts (
                    order_number, document_hash_sha256, attempted_employee_code, attempted_at
                ) VALUES (?, ?, ?, ?)
                """,
                (note.order_number, note.document_hash_sha256, employee_code, created_at),
            )
            self.connection.commit()
            raise DuplicateDocumentError(
                f"Pedido {note.order_number} ou documento já registrado."
            ) from error

    def pending(self) -> list[dict[str, Any]]:
        """Retorna itens locais que ainda aguardam identificação ou integração."""
        rows = self.connection.execute(
            "SELECT * FROM credit_queue ORDER BY id"
        ).fetchall()
        return [dict(row) for row in rows]

    def export_pending(self, export_folder: Path) -> Path:
        """Exporta pendências para JSON, sem alterar status nem removê-las da fila."""
        export_folder.mkdir(parents=True, exist_ok=True)
        destination = export_folder / (
            "santrirank-pendentes-" + datetime.now().strftime("%Y%m%d-%H%M%S-%f") + ".json"
        )
        payload = {
            "schema_version": 1,
            "warning": "Exportação local pendente; não representa crédito confirmado no aplicativo.",
            "records": self.pending(),
        }
        destination.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
        return destination

    def close(self) -> None:
        """Fecha a conexão SQLite aberta pelo processo."""
        self.connection.close()

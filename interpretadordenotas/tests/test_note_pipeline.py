from pathlib import Path

import pytest

from config.settings import load_settings
from parser.santri_note_parser import NoteParseError, parse_note
from scoring.score_calculator import ScoringWeights, calculate_score
from sync.credit_dispatcher import CreditDispatcher, DuplicateDocumentError


SAMPLE_NOTE = """
DMC ATACADAO DA CONSTRUCAO LTDA - FILIAL VP
Pedido: 6.397.023
Local: EXPEDIÇÃO
32,000 MT
1 PC
"""


def test_parser_extracts_order_and_mt_pc_quantities():
    note = parse_note(SAMPLE_NOTE)

    assert note.order_number == "6397023"
    assert [(item.quantity, item.unit) for item in note.items] == [(32.0, "MT"), (1.0, "PC")]
    assert len(note.document_hash_sha256) == 64


def test_parser_rejects_documents_without_supported_items():
    with pytest.raises(NoteParseError, match="unidade suportada"):
        parse_note("Pedido: 6.397.023\nProduto sem quantidade reconhecível")


def test_score_matches_android_formula_for_mt_and_pc():
    note = parse_note(SAMPLE_NOTE)
    score = calculate_score(note, ScoringWeights())

    assert score.item_count == 2
    assert score.unit_count == 1
    assert score.linear_meters == 32
    assert score.total == 111.0


def test_score_rounds_half_cent_like_kotlin_round_to_int():
    note = parse_note(SAMPLE_NOTE)
    weights = ScoringWeights(
        pts_nota=0.005,
        pts_item_sku=0,
        pts_unidade=0,
        pts_m3=0,
        pts_m2=0,
        pts_ml=0,
        pts_saco=0,
    )

    assert calculate_score(note, weights).total == 0.01


def test_score_applies_mix_load_and_error_adjustments():
    text = "Pedido: 6.397.024\n2 M3\n" + "\n".join("1 PC" for _ in range(10))
    score = calculate_score(parse_note(text), error_count=2, item_weight_factor=1.5)

    assert score.item_count == 11
    assert score.mix_bonus == 20
    assert score.load_bonus == 30
    assert score.discount == 10
    assert score.total == 186.4


def test_parser_handles_pedido_on_next_line_and_brazilian_thousands():
    note = parse_note("Pedido:\n6.397.023\n1.250 PC")

    assert note.order_number == "6397023"
    assert note.items[0].quantity == 1250


def test_queue_binds_employee_and_prevents_duplicate_order(tmp_path: Path):
    note = parse_note(SAMPLE_NOTE)
    dispatcher = CreditDispatcher(tmp_path / "queue.sqlite3")
    try:
        dispatcher.queue_credit(note, 111.0, "MAT-007", "Separação conferida", "nota.txt")
        record = dispatcher.pending()[0]
        assert record["employee_code"] == "MAT-007"
        assert record["status"] == "pending"
        assert record["observation"] == "Separação conferida"

        with pytest.raises(DuplicateDocumentError):
            dispatcher.queue_credit(note, 111.0, "MAT-002", source="reimpressao.txt")
        attempts = dispatcher.connection.execute(
            "SELECT COUNT(*) FROM duplicate_attempts"
        ).fetchone()[0]
        assert attempts == 1
    finally:
        dispatcher.close()


def test_same_order_with_changed_document_is_still_rejected(tmp_path: Path):
    first = parse_note(SAMPLE_NOTE)
    reprint = parse_note(SAMPLE_NOTE.replace("32,000 MT", "33,000 MT"))
    assert first.document_hash_sha256 != reprint.document_hash_sha256

    dispatcher = CreditDispatcher(tmp_path / "queue.sqlite3")
    try:
        dispatcher.queue_credit(first, 111.0, "MAT-007")
        with pytest.raises(DuplicateDocumentError):
            dispatcher.queue_credit(reprint, 114.0, "MAT-007")
    finally:
        dispatcher.close()


def test_settings_expand_windows_environment_paths(tmp_path: Path, monkeypatch):
    monkeypatch.setenv("LOCALAPPDATA", str(tmp_path))
    config = tmp_path / "settings.json"
    config.write_text(
        '{"paths":{"database_path":"%LOCALAPPDATA%/SantriRank/queue.sqlite3"}}',
        encoding="utf-8",
    )

    settings = load_settings(config)

    assert settings.database_path == (tmp_path / "SantriRank" / "queue.sqlite3").resolve()


def test_deferred_note_stays_unassigned_and_export_does_not_mark_synced(tmp_path: Path):
    note = parse_note(SAMPLE_NOTE)
    dispatcher = CreditDispatcher(tmp_path / "queue.sqlite3")
    try:
        dispatcher.defer(note, "nota.txt")
        exported = dispatcher.export_pending(tmp_path / "export")
        assert exported.exists()
        assert dispatcher.pending()[0]["status"] == "needs_identification"
    finally:
        dispatcher.close()

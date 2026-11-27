"""Parser conservador de documentos TXT e PDF digital do Santri."""

from __future__ import annotations

import hashlib
import re
import unicodedata
from dataclasses import asdict, dataclass
from pathlib import Path


# Aceita tanto "Pedido: 6.397.023" quanto o pedido na linha seguinte.
ORDER_PATTERN = re.compile(
    r"(?im)\bpedido\b\s*(?:n(?:[º°o.]|umero)?\s*)?[:#-]?\s*(?:\r?\n\s*)?([0-9][0-9./-]{2,})"
)
# Cada unidade reconhecida é convertida para um campo compatível com o app.
ITEM_PATTERN = re.compile(
    r"(?<![\w,])(?P<quantity>\d+(?:[.,]\d{1,3})?)\s*"
    r"(?P<unit>M3|M³|M2|M²|MT|PC|UN|UND|SC)\b",
    re.IGNORECASE,
)


class NoteParseError(ValueError):
    """Documento ausente de campos mínimos ou contendo formato não suportado."""


@dataclass(frozen=True)
class NoteItem:
    quantity: float
    unit: str


@dataclass(frozen=True)
class ParsedNote:
    order_number: str
    items: tuple[NoteItem, ...]
    document_hash_sha256: str

    def to_dict(self) -> dict[str, object]:
        """Serializa a nota para a fila local."""
        return {
            "order_number": self.order_number,
            "items": [asdict(item) for item in self.items],
            "document_hash_sha256": self.document_hash_sha256,
        }


def _decimal(value: str) -> float:
    """Interpreta vírgula decimal brasileira e rejeita números inválidos."""
    if "," in value:
        normalized = value.replace(".", "").replace(",", ".")
    elif "." in value and len(value.rsplit(".", 1)[-1]) == 3:
        normalized = value.replace(".", "")
    else:
        normalized = value
    number = float(normalized)
    if number <= 0 or number != number or number == float("inf"):
        raise NoteParseError(f"Quantidade inválida: {value}")
    return number


def parse_note(text: str) -> ParsedNote:
    """Extrai pedido, unidades suportadas e hash estável do texto da nota."""
    if not text.strip():
        raise NoteParseError("O documento não contém texto.")
    order_match = ORDER_PATTERN.search(text)
    if not order_match:
        raise NoteParseError("Número do pedido não encontrado.")
    order_number = re.sub(r"\D", "", order_match.group(1))
    if not order_number:
        raise NoteParseError("Número do pedido inválido.")

    items = tuple(
        NoteItem(quantity=_decimal(match.group("quantity")), unit=match.group("unit").upper().replace("³", "3").replace("²", "2"))
        for match in ITEM_PATTERN.finditer(text)
    )
    if not items:
        raise NoteParseError("Nenhum item com unidade suportada (MT, PC, M3, M2 ou SC) foi encontrado.")

    canonical = unicodedata.normalize("NFKC", text)
    canonical = " ".join(canonical.casefold().split())
    digest = hashlib.sha256(canonical.encode("utf-8")).hexdigest()
    return ParsedNote(order_number, items, digest)


def read_note_file(path: Path) -> ParsedNote:
    """Lê TXT ou PDF digital; falha explicitamente para PDF sem camada de texto."""
    suffix = path.suffix.casefold()
    if suffix == ".txt":
        text = path.read_text(encoding="utf-8-sig")
    elif suffix == ".pdf":
        try:
            from pypdf import PdfReader
        except ImportError as error:
            raise NoteParseError("Instale as dependências para ler PDF: pip install -r requirements.txt") from error
        reader = PdfReader(str(path))
        text = "\n".join(page.extract_text() or "" for page in reader.pages)
        if not text.strip():
            raise NoteParseError("PDF sem camada de texto; OCR de imagem ainda não está disponível.")
    else:
        raise NoteParseError(f"Formato não suportado: {suffix or '(sem extensão)'}")
    return parse_note(text)

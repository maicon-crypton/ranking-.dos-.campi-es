"""Cópia tipada da fórmula central de pontuação do aplicativo Android."""

from __future__ import annotations

from dataclasses import asdict, dataclass
from math import floor, isfinite

from parser.santri_note_parser import ParsedNote


@dataclass(frozen=True)
class ScoringWeights:
    """Pesos padrão equivalentes à entidade ScoringWeights do Android."""

    pts_nota: float = 10.0
    pts_item_sku: float = 2.0
    pts_unidade: float = 1.0
    pts_m3: float = 50.0
    pts_m2: float = 5.0
    pts_ml: float = 3.0
    pts_saco: float = 4.0
    bonus_mix_itens: float = 20.0
    bonus_carga_m3: float = 30.0
    penalidade_erro: float = 5.0

    def as_dict(self) -> dict[str, float]:
        return asdict(self)


@dataclass(frozen=True)
class ScoreBreakdown:
    """Detalhamento para exibição e auditoria local."""

    item_count: int
    unit_count: float
    volume_m3: float
    area_m2: float
    linear_meters: float
    bag_count: float
    base: float
    item_points: float
    unit_points: float
    volume_points: float
    area_points: float
    linear_points: float
    bag_points: float
    mix_bonus: float
    load_bonus: float
    discount: float
    total: float


def calculate_score(
    note: ParsedNote,
    weights: ScoringWeights = ScoringWeights(),
    error_count: int = 0,
    item_weight_factor: float = 1.0,
) -> ScoreBreakdown:
    """Aplica fórmula e limites do SantriScoreCalculator do Android."""
    if error_count < 0:
        raise ValueError("A quantidade de erros não pode ser negativa.")
    factor = item_weight_factor if isfinite(item_weight_factor) else 1.0
    factor = min(1.2, max(0.8, factor))
    units = sum(item.quantity for item in note.items if item.unit in {"PC", "UN", "UND"})
    volume = sum(item.quantity for item in note.items if item.unit == "M3")
    area = sum(item.quantity for item in note.items if item.unit == "M2")
    meters = sum(item.quantity for item in note.items if item.unit in {"MT", "M"})
    bags = sum(item.quantity for item in note.items if item.unit == "SC")
    item_count = len(note.items)

    base = weights.pts_nota
    item_points = item_count * weights.pts_item_sku * factor
    unit_points = units * weights.pts_unidade
    volume_points = volume * weights.pts_m3
    area_points = area * weights.pts_m2
    linear_points = meters * weights.pts_ml
    bag_points = bags * weights.pts_saco
    mix_bonus = weights.bonus_mix_itens if item_count >= 10 else 0.0
    load_bonus = weights.bonus_carga_m3 if volume >= 2.0 else 0.0
    discount = error_count * weights.penalidade_erro
    raw_total = (
        base + item_points + unit_points + volume_points + area_points + linear_points
        + bag_points + mix_bonus + load_bonus - discount
    )
    total = floor(max(raw_total, 0.0) * 100 + 0.5) / 100

    return ScoreBreakdown(
        item_count, units, volume, area, meters, bags, base, item_points, unit_points,
        volume_points, area_points, linear_points, bag_points, mix_bonus, load_bonus,
        discount, total,
    )

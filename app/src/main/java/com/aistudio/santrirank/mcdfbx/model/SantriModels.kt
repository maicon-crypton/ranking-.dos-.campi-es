package com.aistudio.santrirank.mcdfbx.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Relation
import androidx.room.TypeConverters
import java.util.Date
import kotlin.math.roundToInt

// ============================================================================
// ENUMS DO DOMÍNIO
// ============================================================================

/** Situação funcional do colaborador na madeireira. */
enum class SituacaoColaborador {
    ATIVO, FERIAS, FOLGA, AFASTADO
}

/** Nível de desempenho do colaborador, definido pela pontuação acumulada. */
enum class TierColaborador {
    BRONZE, PRATA, OURO, MESTRE
}

/** Perfil de acesso do usuário no sistema. */
enum class UsuarioRole {
    ADMIN, OPERADOR
}

/** Status de auditoria de uma nota Santri. */
enum class StatusAuditoria {
    PENDENTE, APROVADO, REVISAR, ESTORNADA
}

/** Origem do registro da nota (foto OCR ou anexo de galeria). */
enum class OrigemRegistro {
    OCR_FOTO, ANEXO_GALERIA
}

/** Tipo de ação administrativa gravada no log de auditoria. */
enum class TipoAcaoAuditoria {
    CADASTRO_COLABORADOR, EDICAO_COLABORADOR, REMOCAO_COLABORADOR,
    AJUSTE_PONTOS, APROVACAO_NOTA, REVISAO_NOTA, BONUS_RAPIDO,
    ALTERACAO_META_SEMANAL, EXPORTACAO_RELATORIO, CRIACAO_USUARIO_ADMIN
}

// ============================================================================
// ENTIDADES ROOM
// ============================================================================

/**
 * Colaborador da madeireira. Armazena dados cadastrais, cargo, setor,
 * situação, pontos de ajuste ADM e senha hasheada (PBKDF2).
 */
@Entity(tableName = "colaboradores")
data class Colaborador(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id") val id: Long = 0,

    @ColumnInfo(name = "user_id") val userId: String = "",
    @ColumnInfo(name = "nome") val nome: String = "",
    @ColumnInfo(name = "codigo_santri") val codigoSantri: String = "",
    @ColumnInfo(name = "senha_hash") val senhaHash: String = "",
    @ColumnInfo(name = "cargo") val cargo: String = "",
    @ColumnInfo(name = "setor") val setor: String = "",
    @ColumnInfo(name = "situacao") val situacao: String = SituacaoColaborador.ATIVO.name,
    @ColumnInfo(name = "dias_trabalhados") val diasTrabalhados: Int = 0,
    @ColumnInfo(name = "pontos_ajuste_adm") val pontosAjusteAdm: Double = 0.0,
    @ColumnInfo(name = "ativo") val ativo: Boolean = true,
    @ColumnInfo(name = "created_at") val createdAt: Date = Date(),
    @ColumnInfo(name = "updated_at") val updatedAt: Date = Date()
)

/**
 * Nota Santri (fiscal/romaneio) registrada por um colaborador.
 * Inclui o hash da foto para auxiliar na detecção de notas duplicadas.
 */
@Entity(tableName = "notas_santri")
data class NotaSantri(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id") val id: Long = 0,

    @ColumnInfo(name = "user_id") val userId: String = "",
    @ColumnInfo(name = "numero_nota_santri") val numeroNotaSantri: String = "",
    @ColumnInfo(name = "mes_referencia") val mesReferencia: String = "",
    @ColumnInfo(name = "colaborador_id") val colaboradorId: Long = 0,
    @ColumnInfo(name = "colaborador_nome") val colaboradorNome: String = "",
    @ColumnInfo(name = "data_emissao") val dataEmissao: Date = Date(),
    @ColumnInfo(name = "qtd_itens") val qtdItens: Int = 0,
    @ColumnInfo(name = "qtd_unidades") val qtdUnidades: Double = 0.0,
    @ColumnInfo(name = "volume_m3") val volumeM3: Double = 0.0,
    @ColumnInfo(name = "area_m2") val areaM2: Double = 0.0,
    @ColumnInfo(name = "metros_lineares") val metrosLineares: Double = 0.0,
    @ColumnInfo(name = "qtd_sacos") val qtdSacos: Double = 0.0,
    @ColumnInfo(name = "tempo_execucao_min") val tempoExecucaoMin: Int = 0,
    @ColumnInfo(name = "qtd_erros") val qtdErros: Int = 0,
    @ColumnInfo(name = "observacao_qualidade") val observacaoQualidade: String = "",
    @ColumnInfo(name = "origem_registro") val origemRegistro: String = OrigemRegistro.OCR_FOTO.name,
    @ColumnInfo(name = "pontuacao_total") val pontuacaoTotal: Double = 0.0,
    @ColumnInfo(name = "texto_ocr_bruto") val textoOcrBruto: String = "",
    @ColumnInfo(name = "analise_ia_json", defaultValue = "''") val analiseIaJson: String = "",
    @ColumnInfo(name = "foto_uri") val fotoUri: String = "",
    @ColumnInfo(name = "foto_hash_sha256") val fotoHashSha256: String = "",
    @ColumnInfo(name = "status_auditoria") val statusAuditoria: String = StatusAuditoria.PENDENTE.name,
    @ColumnInfo(name = "created_at") val createdAt: Date = Date(),
    @ColumnInfo(name = "updated_at") val updatedAt: Date = Date()
)

/**
 * Observação da equipe vinculada a um colaborador.
 */
@Entity(tableName = "observacoes_equipe")
data class ObservacaoEquipe(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id") val id: Long = 0,

    @ColumnInfo(name = "colaborador_id") val colaboradorId: Long = 0,
    @ColumnInfo(name = "autor_nome") val autorNome: String = "",
    @ColumnInfo(name = "titulo") val titulo: String = "",
    @ColumnInfo(name = "detalhe") val detalhe: String = "",
    @ColumnInfo(name = "created_at") val createdAt: Date = Date()
)

/**
 * Log de auditoria imutável. Toda ação administrativa grava uma entrada aqui.
 */
@Entity(tableName = "audit_logs")
data class AuditLog(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id") val id: Long = 0,

    @ColumnInfo(name = "actor_username") val actorUsername: String = "",
    @ColumnInfo(name = "action_type") val actionType: String = "",
    @ColumnInfo(name = "target_id") val targetId: Long = 0,
    @ColumnInfo(name = "previous_value") val previousValue: String = "",
    @ColumnInfo(name = "new_value") val newValue: String = "",
    @ColumnInfo(name = "reason") val reason: String = "",
    @ColumnInfo(name = "created_at") val createdAt: Date = Date()
)

/**
 * Pesos de pontuação usados por [SantriScoreCalculator].
 */
@Entity(tableName = "scoring_weights")
data class ScoringWeights(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id") val id: Long = 0,

    @ColumnInfo(name = "pts_nota") val ptsNota: Double = 10.0,
    @ColumnInfo(name = "pts_item_sku") val ptsItemSku: Double = 2.0,
    @ColumnInfo(name = "pts_unidade") val ptsUnidade: Double = 1.0,
    @ColumnInfo(name = "pts_m3") val ptsM3: Double = 50.0,
    @ColumnInfo(name = "pts_m2") val ptsM2: Double = 5.0,
    @ColumnInfo(name = "pts_ml") val ptsMl: Double = 3.0,
    @ColumnInfo(name = "pts_saco") val ptsSaco: Double = 4.0,
    @ColumnInfo(name = "bonus_mix_itens") val bonusMixItens: Double = 20.0,
    @ColumnInfo(name = "bonus_carga_m3") val bonusCargaM3: Double = 30.0,
    @ColumnInfo(name = "penalidade_erro") val penalidadeErro: Double = 5.0,
    @ColumnInfo(name = "meta_diaria_pontos") val metaDiariaPontos: Double = 100.0
)

/**
 * Configuração do workspace da empresa/unidade (meta semanal, PIN gerencial hasheado).
 */
@Entity(tableName = "user_workspace")
data class UserWorkspace(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id") val id: Long = 0,

    @ColumnInfo(name = "user_id") val userId: String = "",
    @ColumnInfo(name = "company_name") val companyName: String = "Madeireira Campeão da Construção",
    @ColumnInfo(name = "manager_pin_hash") val managerPinHash: String = "",
    @ColumnInfo(name = "meta_semanal_unidade") val metaSemanalUnidade: Double = 500.0
)

/**
 * Conta de acesso identificada pelo usuário, hash da senha e perfil.
 */
@Entity(tableName = "app_users", primaryKeys = ["username"])
data class AppUser(
    @ColumnInfo(name = "username") val username: String = "",
    @ColumnInfo(name = "password_hash") val passwordHash: String = "",
    @ColumnInfo(name = "role") val role: String = UsuarioRole.OPERADOR.name,
    @ColumnInfo(name = "failed_attempts") val failedAttempts: Int = 0,
    @ColumnInfo(name = "locked_until_epoch_ms") val lockedUntilEpochMs: Long = 0,
    @ColumnInfo(name = "primeiro_acesso") val primeiroAcesso: Boolean = false
)

// ============================================================================
// RESULTADO DE PONTUAÇÃO
// ============================================================================

/** Detalhamento do cálculo de pontos de uma nota. */
data class ScoreBreakdown(
    val base: Double,
    val itensPts: Double,
    val unidadesPts: Double,
    val m3Pts: Double,
    val m2Pts: Double,
    val mlPts: Double,
    val sacosPts: Double,
    val bonusMix: Double,
    val bonusCarga: Double,
    val desconto: Double,
    val total: Double
)

// ============================================================================
// MODELOS DE UI
// ============================================================================

/** Entrada do ranking exibida na tela RankingScreen. */
data class RankingEntry(
    val posicao: Int,
    val colaboradorId: Long,
    val nome: String,
    val cargo: String,
    val setor: String,
    val situacao: String,
    val pontos: Double,
    val volumeM3: Double,
    val qtdNotas: Int,
    val tier: TierColaborador,
    val avatarIniciais: String
)

/** Resultado da autenticação. */
sealed class AuthResult {
    data class Success(val username: String, val role: UsuarioRole, val primeiroAcesso: Boolean = false) : AuthResult()
    data class Error(val message: String) : AuthResult()
    data class Locked(val secondsRemaining: Int) : AuthResult()
}

// ============================================================================
// CALCULADORA CENTRALIZADA DE PONTUAÇÃO
// ============================================================================

/**
 * Calcula a pontuação detalhada de uma nota considerando volume, unidades,
 * bônus e penalidades. A regra é centralizada aqui para garantir consistência
 * entre scanner, relatórios e ajustes ADM.
 */
object SantriScoreCalculator {

    /**
     * @param qtdItens quantidade de itens/SKUs da nota
     * @param qtdUnidades quantidade de unidades
     * @param volumeM3 volume em metros cúbicos
     * @param areaM2 área em metros quadrados
     * @param metrosLineares metros lineares
     * @param qtdSacos quantidade de sacos/embalagens
     * @param weights pesos de pontuação configurados
     * @param qtdErros quantidade de erros detectados (penalidade)
     */
    fun calculate(
        qtdItens: Int,
        qtdUnidades: Double,
        volumeM3: Double,
        areaM2: Double,
        metrosLineares: Double,
        qtdSacos: Double,
        weights: ScoringWeights,
        qtdErros: Int = 0,
        itemWeightFactor: Double = 1.0
    ): ScoreBreakdown {
        // Pontuação base fixa por nota registrada
        val base = weights.ptsNota
        // Pontos por item/SKU
        val safeItemWeightFactor = itemWeightFactor.takeIf { it.isFinite() }?.coerceIn(0.8, 1.2) ?: 1.0
        val itensPts = qtdItens * weights.ptsItemSku * safeItemWeightFactor
        // Pontos por unidade
        val unidadesPts = qtdUnidades * weights.ptsUnidade
        // Pontos por volume (m³)
        val m3Pts = volumeM3 * weights.ptsM3
        // Pontos por área (m²)
        val m2Pts = areaM2 * weights.ptsM2
        // Pontos por metros lineares
        val mlPts = metrosLineares * weights.ptsMl
        // Pontos por saco/embalagem
        val sacosPts = qtdSacos * weights.ptsSaco
        // Bônus de mix: nota com 10+ itens ganha bônus fixo
        val bonusMix = if (qtdItens >= 10) weights.bonusMixItens else 0.0
        // Bônus de carga: volume >= 2.0 m³ ganha bônus fixo
        val bonusCarga = if (volumeM3 >= 2.0) weights.bonusCargaM3 else 0.0
        // Desconto por erros
        val desconto = qtdErros * weights.penalidadeErro
        // Bruto somando todos os componentes
        val bruto = base + itensPts + unidadesPts + m3Pts + m2Pts + mlPts + sacosPts + bonusMix + bonusCarga - desconto
        // Arredonda para 2 casas e garante não-negativo
        val total = ((bruto.coerceAtLeast(0.0) * 100).roundToInt() / 100.0)
        return ScoreBreakdown(base, itensPts, unidadesPts, m3Pts, m2Pts, mlPts, sacosPts, bonusMix, bonusCarga, desconto, total)
    }

    /**
     * Determina o tier do colaborador com base na pontuação acumulada.
     */
    fun tierFor(pontos: Double): TierColaborador = when {
        pontos >= 5000.0 -> TierColaborador.MESTRE
        pontos >= 3000.0 -> TierColaborador.OURO
        pontos >= 1500.0 -> TierColaborador.PRATA
        else -> TierColaborador.BRONZE
    }

    /**
     * Iniciais do avatar a partir do nome completo (ex: "Rafael Souza" -> "RS").
     */
    fun iniciaisAvatar(nome: String): String {
        val partes = nome.trim().split(" ", limit = 2)
        return if (partes.size >= 2) {
            "${partes[0].firstOrNull() ?: ""}${partes[1].firstOrNull() ?: ""}".uppercase()
        } else {
            partes.firstOrNull()?.firstOrNull()?.uppercase().toString() ?: "?"
        }
    }
}

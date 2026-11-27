package com.aistudio.santrirank.mcdfbx.ai

import android.content.Context
import android.net.Uri
import com.aistudio.santrirank.mcdfbx.model.NotaSantri
import com.aistudio.santrirank.mcdfbx.model.SantriScoreCalculator
import com.aistudio.santrirank.mcdfbx.model.ScoringWeights
import com.google.firebase.ai.type.Content
import com.google.firebase.ai.type.InlineDataPart
import com.google.firebase.ai.type.TextPart
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale

data class DetectedNoteItem(
    val description: String,
    val quantity: Double,
    val unit: String,
    val category: String,
    val complexity: Int,
    val confidence: Double,
    val reason: String
)

data class AiLearningExample(
    val description: String,
    val category: String,
    val complexity: Int
)

data class AiAnalysisSnapshot(
    val itemWeightFactor: Double = 1.0,
    val items: List<DetectedNoteItem> = emptyList()
)

fun calculateItemWeightFactor(items: List<DetectedNoteItem>, itemCount: Int): Double {
    val count = maxOf(itemCount.coerceAtLeast(0), items.size)
    if (count == 0) return 1.0

    val itemFactors = items.take(count).map { item ->
        if (item.confidence < 0.65) {
            1.0
        } else {
            0.8 + (item.complexity.coerceIn(1, 5) - 1) * 0.1
        }
    }
    val unclassifiedItems = (count - itemFactors.size).coerceAtLeast(0)
    return ((itemFactors.sum() + unclassifiedItems) / count).coerceIn(0.8, 1.2)
}

data class OcrScanResult(
    val numeroNota: String = "",
    val dataEmissao: String = "",
    val qtdItens: Int = 0,
    val qtdUnidades: Double = 0.0,
    val volumeM3: Double = 0.0,
    val areaM2: Double = 0.0,
    val metrosLineares: Double = 0.0,
    val qtdSacos: Double = 0.0,
    val tempoExecucaoMin: Int = 0,
    val qtdErros: Int = 0,
    val observacaoQualidade: String = "",
    val textoOcrBruto: String = "",
    val items: List<DetectedNoteItem> = emptyList(),
    val sucesso: Boolean = false,
    val erro: String? = null
) {
    fun toNotaSantri(
        colaboradorId: Long,
        colaboradorNome: String,
        fotoUri: String,
        fotoHashSha256: String,
        weights: ScoringWeights = ScoringWeights(),
        itemWeightFactor: Double = 1.0,
        analysisJson: String = ""
    ): NotaSantri {
        val now = java.util.Date()
        val parsedIssueDate = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).run {
            isLenient = false
            parse(dataEmissao, ParsePosition(0))
        }?.takeIf { dataEmissao.length == 10 }
        val issueDate = parsedIssueDate ?: now
        val referenceMonth = SimpleDateFormat("MM/yyyy", Locale("pt", "BR"))
            .format(issueDate)
        val score = SantriScoreCalculator.calculate(
            qtdItens = qtdItens,
            qtdUnidades = qtdUnidades,
            volumeM3 = volumeM3,
            areaM2 = areaM2,
            metrosLineares = metrosLineares,
            qtdSacos = qtdSacos,
            weights = weights,
            qtdErros = qtdErros,
            itemWeightFactor = itemWeightFactor
        ).total
        return NotaSantri(
            colaboradorId = colaboradorId,
            colaboradorNome = colaboradorNome,
            numeroNotaSantri = numeroNota,
            mesReferencia = referenceMonth,
            dataEmissao = issueDate,
            qtdItens = qtdItens,
            qtdUnidades = qtdUnidades,
            volumeM3 = volumeM3,
            areaM2 = areaM2,
            metrosLineares = metrosLineares,
            qtdSacos = qtdSacos,
            tempoExecucaoMin = tempoExecucaoMin,
            qtdErros = qtdErros,
            observacaoQualidade = observacaoQualidade,
            origemRegistro = com.aistudio.santrirank.mcdfbx.model.OrigemRegistro.OCR_FOTO.name,
            pontuacaoTotal = score,
            textoOcrBruto = textoOcrBruto,
            analiseIaJson = analysisJson,
            fotoUri = fotoUri,
            fotoHashSha256 = fotoHashSha256,
            statusAuditoria = com.aistudio.santrirank.mcdfbx.model.StatusAuditoria.PENDENTE.name,
            createdAt = now,
            updatedAt = now
        )
    }
}

sealed class OcrResult {
    data class Success(val result: OcrScanResult) : OcrResult()
    data class Failure(val exception: Exception) : OcrResult()
}

class SantriAiOcrService(private val context: Context) {

    private val modelName = "gemini-2.5-flash"

    suspend fun scanDocument(imageUri: Uri): OcrResult {
        return try {
            val bytes = withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(imageUri)?.use { it.readBytes() }
                    ?: throw IllegalStateException("Não foi possível ler a imagem selecionada.")
            }
            analyzeImage(
                imageBytes = bytes,
                mimeType = context.contentResolver.getType(imageUri) ?: "image/jpeg"
            )
        } catch (error: Exception) {
            OcrResult.Failure(error)
        }
    }

    suspend fun analyzeImage(
        imageBytes: ByteArray,
        mimeType: String,
        examples: List<AiLearningExample> = emptyList()
    ): OcrResult = withContext(Dispatchers.IO) {
        try {
            if (imageBytes.isEmpty()) throw IllegalArgumentException("A imagem está vazia.")
            if (mimeType !in SUPPORTED_IMAGE_TYPES) {
                throw IllegalArgumentException("Formato de imagem não suportado para análise.")
            }

            val firebaseApp = com.google.firebase.FirebaseApp.initializeApp(context)
                ?: com.google.firebase.FirebaseApp.getInstance()
            val model = com.google.firebase.ai.FirebaseAI.getInstance(firebaseApp)
                .generativeModel(modelName)
            val examplesText = examples.take(MAX_LEARNING_EXAMPLES).joinToString("\n") { example ->
                "- item=${example.description.take(MAX_EXAMPLE_DESCRIPTION_LENGTH)}; " +
                    "categoria=${example.category.take(60)}; complexidade=${example.complexity.coerceIn(1, 5)}/5"
            }.ifBlank { "Ainda não há exemplos de notas aprovadas com itens classificados." }
            val prompt = """
                Leia a imagem de uma nota fiscal ou romaneio de uma madeireira e retorne APENAS um JSON válido.
                Use as chaves: numeroNota, dataEmissao (YYYY-MM-DD), qtdItens (quantidade de SKUs distintos),
                qtdUnidades, volumeM3, areaM2, metrosLineares, qtdSacos, tempoExecucaoMin, qtdErros,
                observacaoQualidade e itens.
                itens deve ser uma lista com descricao, quantidade, unidade, categoria, complexidade (inteiro de 1 a 5),
                confianca (número de 0 a 1) e motivo (frase curta).
                Liste apenas produtos realmente legíveis na imagem; não invente itens ou quantidades.
                Classifique complexidade pela dificuldade de manuseio e processamento no depósito, nunca pelo preço,
                valor comercial, funcionário ou qualquer característica pessoal. Use a mesma categoria e complexidade
                para o mesmo tipo de produto. Se houver dúvida, reduza a confiança e use complexidade 3.
                Use os exemplos de itens de notas anteriormente aprovadas apenas para manter categorias e critérios
                consistentes; eles não substituem o que está visível nesta imagem.
                Exemplos aprovados:
                $examplesText
                Para campos numéricos não identificáveis, use 0; para texto não identificável, use string vazia.
            """.trimIndent()

            val response = model.generateContent(
                Content(
                    parts = listOf(
                        InlineDataPart(imageBytes, mimeType),
                        TextPart(prompt)
                    )
                )
            )
            val responseText = response.text.orEmpty()
            val parsed = parseJsonOcr(responseText)
            if (!parsed.sucesso) {
                throw IllegalStateException(parsed.erro ?: "Não foi possível interpretar a nota.")
            }
            OcrResult.Success(parsed)
        } catch (error: Exception) {
            OcrResult.Failure(error)
        }
    }

    private fun parseJsonOcr(text: String): OcrScanResult {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) {
            return OcrScanResult(
                sucesso = false,
                erro = "A IA não retornou os dados da nota em um formato válido.",
                textoOcrBruto = text
            )
        }

        return try {
            val obj = JsonParser.parseString(text.substring(start, end + 1)).asJsonObject
            val items = obj.getAsJsonArray("itens")
                ?.take(MAX_ANALYZED_ITEMS)
                ?.mapNotNull { it.asJsonObjectOrNull() }
                .orEmpty()
                .map { item ->
                    DetectedNoteItem(
                        description = item.string("descricao").take(MAX_ITEM_DESCRIPTION_LENGTH),
                        quantity = item.number("quantidade").coerceAtLeast(0.0),
                        unit = item.string("unidade").take(40),
                        category = item.string("categoria").take(60),
                        complexity = item.number("complexidade").toInt().coerceIn(1, 5),
                        confidence = item.number("confianca").coerceIn(0.0, 1.0),
                        reason = item.string("motivo").take(MAX_ITEM_REASON_LENGTH)
                    )
                }
                .filter { it.description.isNotBlank() }
            val result = OcrScanResult(
                numeroNota = obj.string("numeroNota").take(80),
                dataEmissao = obj.string("dataEmissao").take(10),
                qtdItens = maxOf(obj.number("qtdItens").toInt().coerceAtLeast(0), items.size),
                qtdUnidades = obj.number("qtdUnidades").coerceAtLeast(0.0),
                volumeM3 = obj.number("volumeM3").coerceAtLeast(0.0),
                areaM2 = obj.number("areaM2").coerceAtLeast(0.0),
                metrosLineares = obj.number("metrosLineares").coerceAtLeast(0.0),
                qtdSacos = obj.number("qtdSacos").coerceAtLeast(0.0),
                tempoExecucaoMin = obj.number("tempoExecucaoMin").toInt().coerceAtLeast(0),
                qtdErros = obj.number("qtdErros").toInt().coerceAtLeast(0),
                observacaoQualidade = obj.string("observacaoQualidade").take(MAX_ITEM_REASON_LENGTH),
                textoOcrBruto = text,
                items = items,
                sucesso = true
            )
            if (result.qtdItens == 0 && result.qtdUnidades == 0.0 && result.volumeM3 == 0.0 &&
                result.areaM2 == 0.0 && result.metrosLineares == 0.0 && result.qtdSacos == 0.0
            ) {
                result.copy(
                    sucesso = false,
                    erro = "Não encontrei itens legíveis. Anexe uma foto mais nítida e tente novamente."
                )
            } else {
                result
            }
        } catch (error: Exception) {
            OcrScanResult(
                sucesso = false,
                erro = "Falha ao interpretar os itens da nota.",
                textoOcrBruto = text
            )
        }
    }

    private fun JsonElement.asJsonObjectOrNull(): JsonObject? =
        takeUnless { it.isJsonNull || !it.isJsonObject }?.asJsonObject

    private fun JsonObject.string(key: String): String =
        get(key)?.takeUnless { it.isJsonNull }?.asString.orEmpty()

    private fun JsonObject.number(key: String): Double =
        get(key)?.takeUnless { it.isJsonNull }?.asDouble ?: 0.0

    private companion object {
        val SUPPORTED_IMAGE_TYPES = setOf("image/jpeg", "image/png", "image/webp")
        const val MAX_LEARNING_EXAMPLES = 8
        const val MAX_ANALYZED_ITEMS = 100
        const val MAX_EXAMPLE_DESCRIPTION_LENGTH = 100
        const val MAX_ITEM_DESCRIPTION_LENGTH = 160
        const val MAX_ITEM_REASON_LENGTH = 240
    }
}

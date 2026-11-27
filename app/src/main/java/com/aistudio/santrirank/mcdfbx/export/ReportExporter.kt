package com.aistudio.santrirank.mcdfbx.export

import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.core.content.FileProvider
import com.aistudio.santrirank.mcdfbx.model.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.Locale

/**
 * Gera arquivos de relatório e disponibiliza seus URIs para compartilhamento via FileProvider.
 * Os arquivos são armazenados no diretório de Downloads específico do aplicativo.
 */
object ReportExporter {

    private val sdf = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
    private val fileSdf = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())

    /**
     * Exporta relatório em CSV contendo KPIs, ranking por colaborador e detalhe das notas.
     * Retorna o Uri do arquivo gerado (para compartilhamento).
     */
    fun exportarCsv(
        context: Context,
        colaboradores: List<Colaborador>,
        notas: List<NotaSantri>,
        pesos: ScoringWeights,
        filtros: String
    ): Uri {
        val arquivo = File(
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
            "relatorio_ranking_${fileSdf.format(Date())}.csv"
        )
        arquivo.bufferedWriter().use { w ->
            w.write("Relatório de Auditoria - Ranking dos Campeões\n")
            w.write("Empresa: Madeireira Campeão da Construção • Brasília-DF\n")
            w.write("Gerado em: ${sdf.format(Date())}\n")
            w.write("Filtros: $filtros\n")
            w.write("\n")

            // KPIs
            w.write("=== KPIs ===\n")
            w.write("Total de colaboradores,${colaboradores.size}\n")
            w.write("Total de notas,${notas.size}\n")
            w.write("Volume total (m³),${notas.sumOf { it.volumeM3 }}\n")
            w.write("Pontos totais,${notas.sumOf { it.pontuacaoTotal }}\n")
            w.write("Notas pendentes,${notas.count { it.statusAuditoria == StatusAuditoria.PENDENTE.name }}\n")
            w.write("Notas aprovadas,${notas.count { it.statusAuditoria == StatusAuditoria.APROVADO.name }}\n")
            w.write("\n")

            // Ranking por colaborador
            w.write("=== Ranking por Colaborador ===\n")
            w.write("Posição,Nome,Cargo,Setor,Pontuação,Volume (m³),Qtd Notas\n")
            val ranking = construirRanking(colaboradores, notas, pesos)
            for ((i, entry) in ranking.withIndex()) {
                w.write("${i + 1},${entry.nome},${entry.cargo},${entry.setor},${entry.pontos},${entry.volumeM3},${entry.qtdNotas}\n")
            }
            w.write("\n")

            // Detalhe das notas
            w.write("=== Detalhe das Notas ===\n")
            w.write("Número,Colaborador,Data Emissão,Itens,Unidades,Volume (m³),Área (m²),Metros Lineares,Sacos,Erros,Pontuação,Status\n")
            for (n in notas) {
                w.write("${n.numeroNotaSantri},${n.colaboradorNome},${sdf.format(n.dataEmissao)},${n.qtdItens},${n.qtdUnidades},${n.volumeM3},${n.areaM2},${n.metrosLineares},${n.qtdSacos},${n.qtdErros},${n.pontuacaoTotal},${n.statusAuditoria}\n")
            }
        }

        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            arquivo
        )
    }

    /** Cria o arquivo de relatório com extensão PDF e retorna sua URI para compartilhamento. */
    fun exportarPdf(
        context: Context,
        colaboradores: List<Colaborador>,
        notas: List<NotaSantri>,
        pesos: ScoringWeights,
        filtros: String
    ): Uri {
        val arquivo = File(
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
            "relatorio_ranking_${fileSdf.format(Date())}.pdf"
        )

        // Escreve a estrutura inicial do arquivo com extensão PDF.
        arquivo.bufferedWriter().use { w ->
            w.write("%PDF-1.4\n")
            w.write("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")
            w.write("2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n")
            w.write("3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R >>\nendobj\n")
            w.write("4 0 obj\n<< /Length 1 >>\nstream\nendstream\nendobj\n")
            w.write("xref\n0 5\n0000000000 65535 f \n")
            w.write("trailer\n<< /Size 5 /Root 1 0 R >>\nstartxref\n0\n%%EOF\n")
            // Acrescenta os dados em texto após o marcador de fim do arquivo.
            w.write("\n---\nRelatório de Auditoria - Ranking dos Campeões\n")
            w.write("Empresa: Madeireira Campeão da Construção • Brasília-DF\n")
            w.write("Gerado em: ${sdf.format(Date())}\n")
            w.write("Filtros: $filtros\n\n")
            for (n in notas) {
                w.write("${n.numeroNotaSantri} | ${n.colaboradorNome} | ${n.pontuacaoTotal} pts | ${n.statusAuditoria}\n")
            }
        }

        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            arquivo
        )
    }

    private fun construirRanking(
        colaboradores: List<Colaborador>,
        notas: List<NotaSantri>,
        pesos: ScoringWeights
    ): List<RankingEntry> {
        val notasPorColab = notas.groupBy { it.colaboradorId }
        return colaboradores.map { c ->
            val colNotas = notasPorColab[c.id] ?: emptyList()
            val pontos = colNotas.sumOf { it.pontuacaoTotal } + c.pontosAjusteAdm
            val volume = colNotas.sumOf { it.volumeM3 }
            RankingEntry(
                posicao = 0,
                colaboradorId = c.id,
                nome = c.nome,
                cargo = c.cargo,
                setor = c.setor,
                situacao = c.situacao,
                pontos = pontos,
                volumeM3 = volume,
                qtdNotas = colNotas.size,
                tier = SantriScoreCalculator.tierFor(pontos),
                avatarIniciais = SantriScoreCalculator.iniciaisAvatar(c.nome)
            )
        }.sortedByDescending { it.pontos }.withIndex().map { (i, e) ->
            e.copy(posicao = i + 1)
        }
    }
}

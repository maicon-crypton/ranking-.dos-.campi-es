package com.aistudio.santrirank.mcdfbx.data

import android.content.Context
import com.aistudio.santrirank.mcdfbx.model.*
import com.aistudio.santrirank.mcdfbx.security.LoginGuard
import com.aistudio.santrirank.mcdfbx.security.PasswordHasher
import kotlinx.coroutines.withContext
import java.util.*

/**
 * Acesso a dados de colaboradores, notas, auditoria, pontuação e autenticação.
 * Centraliza as operações persistidas no banco local Room.
 */
class SantriRepository private constructor(
    private val colaboradorDao: ColaboradorDao,
    private val notaSantriDao: NotaSantriDao,
    private val observacaoEquipeDao: ObservacaoEquipeDao,
    private val auditLogDao: AuditLogDao,
    private val scoringWeightsDao: ScoringWeightsDao,
    private val userWorkspaceDao: UserWorkspaceDao,
    private val appUserDao: AppUserDao
) {

    companion object {
        @Volatile private var INSTANCE: SantriRepository? = null
        private const val DEMO_ADMIN_USERNAME = "ADM-001"
        private const val DEMO_ADMIN_PASSWORD = "Campeao8"

        fun getInstance(context: Context): SantriRepository {
            return INSTANCE ?: synchronized(this) {
                val db = SantriDatabase.getDatabase(context)
                SantriRepository(
                    db.colaboradorDao(),
                    db.notaSantriDao(),
                    db.observacaoEquipeDao(),
                    db.auditLogDao(),
                    db.scoringWeightsDao(),
                    db.userWorkspaceDao(),
                    db.appUserDao()
                ).also { INSTANCE = it }
            }
        }
    }

    // =========================================================================
    // COLABORADORES
    // =========================================================================

    suspend fun getAllColaboradores(): List<Colaborador> = colaboradorDao.getAll()

    suspend fun getActiveColaboradores(): List<Colaborador> = colaboradorDao.getActive()

    suspend fun searchColaboradores(query: String): List<Colaborador> =
        colaboradorDao.search("%$query%")

    suspend fun getColaboradorById(id: Long): Colaborador? = colaboradorDao.findById(id)

    suspend fun getColaboradorByCodigo(codigo: String): Colaborador? =
        colaboradorDao.findByCodigo(codigo)

    suspend fun insertColaborador(colaborador: Colaborador): Long =
        colaboradorDao.insert(colaborador)

    suspend fun updateColaborador(colaborador: Colaborador): Int =
        colaboradorDao.update(colaborador)

    suspend fun deleteColaborador(id: Long) = colaboradorDao.deleteById(id)

    // =========================================================================
    // NOTAS SANTRI
    // =========================================================================

    suspend fun getAllNotas(): List<NotaSantri> = notaSantriDao.getAll()

    suspend fun getNotasByColaborador(colaboradorId: Long): List<NotaSantri> =
        notaSantriDao.getByColaborador(colaboradorId)

    suspend fun getNotasByStatus(status: String): List<NotaSantri> =
        notaSantriDao.getByStatus(status)

    suspend fun getNotaById(id: Long): NotaSantri? = notaSantriDao.findById(id)

    suspend fun getNotasByDateRange(start: Long, end: Long): List<NotaSantri> =
        notaSantriDao.getByDateRange(start, end)

    suspend fun insertNota(nota: NotaSantri): Long = notaSantriDao.insert(nota)

    suspend fun updateNota(nota: NotaSantri): Int = notaSantriDao.update(nota)

    /** Verifica anti-duplicidade antes de salvar uma nova nota. */
    suspend fun isNotaDuplicata(
        numeroNota: String,
        fotoHash: String,
        excludingId: Long? = null
    ): Boolean {
        val existentes = notaSantriDao.getAll()
        return existentes.any {
            it.id != excludingId && it.numeroNotaSantri.equals(numeroNota, ignoreCase = true)
        } || existentes.any {
            it.id != excludingId && it.fotoHashSha256.isNotEmpty() &&
                it.fotoHashSha256 == fotoHash
        }
    }

    // =========================================================================
    // OBSERVAÇÕES DA EQUIPE
    // =========================================================================

    suspend fun getObservacoesByColaborador(colaboradorId: Long): List<ObservacaoEquipe> =
        observacaoEquipeDao.getByColaborador(colaboradorId)

    suspend fun insertObservacao(obs: ObservacaoEquipe): Long =
        observacaoEquipeDao.insert(obs)

    suspend fun getRecentObservacoes(limit: Int = 50): List<ObservacaoEquipe> =
        observacaoEquipeDao.getRecent(limit)

    // =========================================================================
    // AUDITORIA
    // =========================================================================

    suspend fun insertAuditLog(
        actor: String,
        action: TipoAcaoAuditoria,
        targetId: Long,
        previous: String,
        novo: String,
        reason: String
    ): Long {
        return auditLogDao.insert(
            AuditLog(
                actorUsername = actor,
                actionType = action.name,
                targetId = targetId,
                previousValue = previous,
                newValue = novo,
                reason = reason,
                createdAt = Date()
            )
        )
    }

    suspend fun getRecentAuditLogs(limit: Int = 50): List<AuditLog> =
        auditLogDao.getRecent(limit)

    // =========================================================================
    // CONFIGURAÇÕES DE PONTUAÇÃO E UNIDADE
    // =========================================================================

    suspend fun getScoringWeights(): ScoringWeights =
        scoringWeightsDao.getLatest() ?: ScoringWeights()

    suspend fun saveScoringWeights(weights: ScoringWeights): Long =
        scoringWeightsDao.insert(weights)

    suspend fun getUserWorkspace(): UserWorkspace =
        userWorkspaceDao.getLatest() ?: UserWorkspace()

    suspend fun saveUserWorkspace(workspace: UserWorkspace): Long =
        userWorkspaceDao.insert(workspace)

    // =========================================================================
    // AUTENTICAÇÃO
    // =========================================================================

    suspend fun authenticate(username: String, password: String): com.aistudio.santrirank.mcdfbx.model.AuthResult {
        val user = appUserDao.findByUsername(username) ?: run {
            val employee = colaboradorDao.findByCodigo(username)
                ?.takeIf { it.ativo && PasswordHasher.verify(password, it.senhaHash) }
                ?: return com.aistudio.santrirank.mcdfbx.model.AuthResult.Error(
                    "Matrícula ou senha inválida."
                )
            val migratedUser = AppUser(
                username = employee.codigoSantri,
                passwordHash = employee.senhaHash,
                role = UsuarioRole.OPERADOR.name
            )
            appUserDao.insert(migratedUser)
            return com.aistudio.santrirank.mcdfbx.model.AuthResult.Success(
                migratedUser.username,
                UsuarioRole.OPERADOR,
                migratedUser.primeiroAcesso
            )
        }

        val lockSeconds = LoginGuard().isLocked(user)
        if (lockSeconds != null) {
            return com.aistudio.santrirank.mcdfbx.model.AuthResult.Locked(lockSeconds)
        }

        val senhaValida = PasswordHasher.verify(password, user.passwordHash)
        if (!senhaValida) {
            val atualizado = LoginGuard().registerFailure(user)
            appUserDao.update(atualizado)
            return com.aistudio.santrirank.mcdfbx.model.AuthResult.Error("Senha inválida")
        }

        val role = UsuarioRole.valueOf(user.role)
        if (role == UsuarioRole.OPERADOR &&
            colaboradorDao.findByCodigo(username)?.ativo == false
        ) {
            return com.aistudio.santrirank.mcdfbx.model.AuthResult.Error(
                "Acesso desativado. Procure o administrador."
            )
        }
        val resetado = LoginGuard().reset(user)
        appUserDao.update(resetado)
        return com.aistudio.santrirank.mcdfbx.model.AuthResult.Success(username, role, user.primeiroAcesso)
    }

    suspend fun getUserRole(username: String): UsuarioRole? {
        val user = appUserDao.findByUsername(username)
        return if (user != null) UsuarioRole.valueOf(user.role) else null
    }

    suspend fun findAppUser(username: String): AppUser? =
        appUserDao.findByUsername(username)

    suspend fun getAllAppUsers(): List<AppUser> =
        appUserDao.getAll()

    suspend fun createAppUserIfAbsent(user: AppUser): Boolean =
        appUserDao.insertIfAbsent(user) != -1L

    suspend fun saveAppUser(user: AppUser) {
        appUserDao.insert(user)
    }

    // =========================================================================
    // DADOS INICIAIS
    // =========================================================================

    suspend fun seedIfEmpty() {
        // Cria as contas padrão somente quando ainda não existem.
        val demoAdmin = appUserDao.findByUsername(DEMO_ADMIN_USERNAME)
        if (demoAdmin == null) {
            val admin = AppUser(
                username = DEMO_ADMIN_USERNAME,
                passwordHash = PasswordHasher.hash(DEMO_ADMIN_PASSWORD),
                role = UsuarioRole.ADMIN.name
            )
            appUserDao.insert(admin)
        }
        
        val demoOperador = appUserDao.findByUsername("MAT-104")
        if (demoOperador == null) {
            val operador = AppUser(
                username = "MAT-104",
                passwordHash = PasswordHasher.hash("1042026"),
                role = UsuarioRole.OPERADOR.name
            )
            appUserDao.insert(operador)
        }

        if (colaboradorDao.getAll().isNotEmpty()) {
            val weights = scoringWeightsDao.getLatest() ?: ScoringWeights()
            notaSantriDao.getAll()
                .filter {
                    it.pontuacaoTotal == 0.0 &&
                        it.textoOcrBruto.startsWith("Texto OCR bruto da nota ")
                }
                .forEach { note ->
                    val points = SantriScoreCalculator.calculate(
                        qtdItens = note.qtdItens,
                        qtdUnidades = note.qtdUnidades,
                        volumeM3 = note.volumeM3,
                        areaM2 = note.areaM2,
                        metrosLineares = note.metrosLineares,
                        qtdSacos = note.qtdSacos,
                        weights = weights,
                        qtdErros = note.qtdErros
                    ).total
                    notaSantriDao.update(note.copy(pontuacaoTotal = points))
                }
            return
        }

        val pesos = ScoringWeights(
            ptsNota = 10.0, ptsItemSku = 2.0, ptsUnidade = 1.0, ptsM3 = 50.0,
            ptsM2 = 5.0, ptsMl = 3.0, ptsSaco = 4.0,
            bonusMixItens = 20.0, bonusCargaM3 = 30.0, penalidadeErro = 5.0,
            metaDiariaPontos = 100.0
        )
        scoringWeightsDao.insert(pesos)

        val workspace = UserWorkspace(
            userId = "workspace_001",
            companyName = "Madeireira Campeão da Construção • Brasília-DF",
            managerPinHash = PasswordHasher.hash("2026"),
            metaSemanalUnidade = 500.0
        )
        userWorkspaceDao.insert(workspace)

        // Dados demonstrativos de colaboradores da unidade de Brasília-DF.
        val nomes = listOf(
            "Rafael Souza", "Camila Ferreira", "Lucas Mendes", "Juliana Rocha",
            "Bruno Almeida", "Patrícia Lima", "Diego Costa"
        )
        val codigos = listOf("MAT-001", "MAT-002", "MAT-003", "MAT-004", "MAT-005", "MAT-006", "MAT-007")
        val cargos = listOf("Mestre", "Ouro", "Prata", "Ouro", "Prata", "Bronze", "Bronze")
        val setores = listOf("Pátio & Corte", "Expedição", "Balcão Brasília", "Pátio & Corte", "Expedição", "Balcão Brasília", "Pátio & Corte")
        val situacoes = listOf("ATIVO", "ATIVO", "ATIVO", "ATIVO", "ATIVO", "FERIAS", "ATIVO")

        val colaboradores = nomes.indices.map { i ->
            Colaborador(
                userId = codigos[i],
                nome = nomes[i],
                codigoSantri = codigos[i],
                senhaHash = PasswordHasher.hash("123456"),
                cargo = cargos[i],
                setor = setores[i],
                situacao = situacoes[i],
                diasTrabalhados = (20 + i * 3),
                pontosAjusteAdm = (i * 50.0),
                ativo = true,
                createdAt = Date(),
                updatedAt = Date()
            )
        }
        colaboradorDao.insertAll(colaboradores)

        // Notas demonstrativas para compor o ranking inicial.
        val agora = System.currentTimeMillis()
        val notas = mutableListOf<NotaSantri>()
        for (i in colaboradores.indices) {
            for (j in 0 until 3) {
                val qtdItens = 8 + (i * 2) + j
                val qtdUnidades = 15.0 + i * 3.0 + j
                val volumeM3 = 1.5 + i * 0.5 + j * 0.3
                val areaM2 = 20.0 + i * 5.0
                val metrosLineares = 10.0 + i * 2.0
                val qtdSacos = 5.0 + i
                val qtdErros = if (i == 6) 2 else 0
                val pontuacao = SantriScoreCalculator.calculate(
                    qtdItens = qtdItens,
                    qtdUnidades = qtdUnidades,
                    volumeM3 = volumeM3,
                    areaM2 = areaM2,
                    metrosLineares = metrosLineares,
                    qtdSacos = qtdSacos,
                    weights = pesos,
                    qtdErros = qtdErros
                ).total
                notas.add(NotaSantri(
                    userId = codigos[i],
                    numeroNotaSantri = "NF-${codigos[i]}-${j + 1}00",
                    mesReferencia = "11/2026",
                    colaboradorId = colaboradores[i].id,
                    colaboradorNome = nomes[i],
                    dataEmissao = Date(agora - (j * 86400000)),
                    qtdItens = qtdItens,
                    qtdUnidades = qtdUnidades,
                    volumeM3 = volumeM3,
                    areaM2 = areaM2,
                    metrosLineares = metrosLineares,
                    qtdSacos = qtdSacos,
                    tempoExecucaoMin = (120 + i * 10),
                    qtdErros = qtdErros,
                    observacaoQualidade = "Nota de exemplo $j",
                    origemRegistro = "OCR_FOTO",
                    pontuacaoTotal = pontuacao,
                    textoOcrBruto = "Texto OCR bruto da nota $j",
                    fotoUri = "",
                    fotoHashSha256 = "",
                    statusAuditoria = "APROVADO",
                    createdAt = Date(agora - (j * 86400000)),
                    updatedAt = Date(agora - (j * 86400000))
                ))
            }
        }
        notaSantriDao.insertAll(notas)
    }

    suspend fun clearAllData() {
        colaboradorDao.deleteAll()
        notaSantriDao.deleteAll()
        observacaoEquipeDao.deleteAll()
        auditLogDao.deleteAll()
        scoringWeightsDao.deleteAll()
        userWorkspaceDao.deleteAll()
        appUserDao.deleteAll()
    }

    // =========================================================================
    // ESTORNO ADMINISTRATIVO DE NOTA
    // =========================================================================

    /** Estorna uma nota: remove seus pontos do colaborador, registra motivo e auditoria. */
    suspend fun estornarNota(notaId: Long, motivo: String, actor: String) {
        val nota = notaSantriDao.findById(notaId) ?: return
        val anteriorStatus = nota.statusAuditoria
        val anteriorPontos = nota.pontuacaoTotal

        // A pontuação aprovada é derivada das notas, então não altera o ajuste ADM.
        val estornada = nota.copy(
            statusAuditoria = com.aistudio.santrirank.mcdfbx.model.StatusAuditoria.ESTORNADA.name,
            pontuacaoTotal = 0.0,
            updatedAt = Date()
        )
        notaSantriDao.update(estornada)

        auditLogDao.insert(
            com.aistudio.santrirank.mcdfbx.model.AuditLog(
                actorUsername = actor,
                actionType = TipoAcaoAuditoria.REVISAO_NOTA.name,
                targetId = notaId,
                previousValue = "status=$anteriorStatus pontos=$anteriorPontos",
                newValue = "status=REVISAR pontos=0 motivo=$motivo",
                reason = "Estorno de nota: $motivo",
                createdAt = Date()
            )
        )
    }

    // =========================================================================
    // ALTERAÇÃO DE SENHA NO PRIMEIRO ACESSO
    // =========================================================================

    /** Altera a senha de um usuário no primeiro acesso e marca o flag como concluído. */
    suspend fun trocarSenhaPrimeiroAcesso(username: String, novaSenha: String) {
        require(novaSenha.length in 4..15) { "A senha deve ter de 4 a 15 caracteres." }
        val user = appUserDao.findByUsername(username) ?: return
        val atualizado = user.copy(
            passwordHash = PasswordHasher.hash(novaSenha),
            primeiroAcesso = false
        )
        appUserDao.update(atualizado)
    }

    /** Marca o primeiro acesso como concluído (sem alterar senha). */
    suspend fun concluirPrimeiroAcesso(username: String) {
        val user = appUserDao.findByUsername(username) ?: return
        val atualizado = user.copy(primeiroAcesso = false)
        appUserDao.update(atualizado)
    }

    // =========================================================================
    // BACKUP E RESTAURAÇÃO CRIPTOGRAFADOS DO BANCO
    // =========================================================================

    /** Exporta o arquivo do banco Room criptografado para o diretório de Downloads. */
    suspend fun backupDatabase(context: android.content.Context): java.io.File? {
        val dbFile = context.getDatabasePath("santri_ranking.db")
        if (!dbFile.exists()) return null
        val pastaDownloads = context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)
        val arquivoSaida = java.io.File(pastaDownloads, "backup_santri_${java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault()).format(java.util.Date())}.enc")
        try {
            criptografarArquivo(dbFile, arquivoSaida, "SantriBackup2026!")
            return arquivoSaida
        } catch (e: Exception) {
            return null
        }
    }

    /** Restaura o banco a partir de um arquivo de backup criptografado. */
    suspend fun restaurarDatabase(context: android.content.Context, arquivo: java.io.File): Boolean {
        val dbFile = context.getDatabasePath("santri_ranking.db")
        try {
            descriptografarArquivo(arquivo, dbFile, "SantriBackup2026!")
            return true
        } catch (e: Exception) {
            return false
        }
    }

    private fun criptografarArquivo(origem: java.io.File, destino: java.io.File, senha: String) {
        val chave = javax.crypto.spec.SecretKeySpec(senha.toByteArray(Charsets.UTF_8), "AES")
        val cifrador = javax.crypto.Cipher.getInstance("AES/ECB/PKCS5Padding")
        cifrador.init(javax.crypto.Cipher.ENCRYPT_MODE, chave)
        java.io.FileInputStream(origem).use { entrada ->
            destino.outputStream().use { saida ->
                saida.write(cifrador.doFinal(entrada.readBytes()))
            }
        }
    }

    private fun descriptografarArquivo(origem: java.io.File, destino: java.io.File, senha: String) {
        val chave = javax.crypto.spec.SecretKeySpec(senha.toByteArray(Charsets.UTF_8), "AES")
        val cifrador = javax.crypto.Cipher.getInstance("AES/ECB/PKCS5Padding")
        cifrador.init(javax.crypto.Cipher.DECRYPT_MODE, chave)
        origem.inputStream().use { entrada ->
            destino.outputStream().use { saida -> saida.write(cifrador.doFinal(entrada.readBytes())) }
        }
    }
}

package com.aistudio.santrirank.mcdfbx

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import android.print.PrintAttributes
import android.print.PrintManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONObject
import com.aistudio.santrirank.mcdfbx.data.SantriRepository
import com.aistudio.santrirank.mcdfbx.ai.AiAnalysisSnapshot
import com.aistudio.santrirank.mcdfbx.ai.AiLearningExample
import com.aistudio.santrirank.mcdfbx.ai.OcrScanResult
import com.aistudio.santrirank.mcdfbx.ai.OcrResult
import com.aistudio.santrirank.mcdfbx.ai.SantriAiOcrService
import com.aistudio.santrirank.mcdfbx.ai.calculateItemWeightFactor
import com.aistudio.santrirank.mcdfbx.model.AuthResult
import com.aistudio.santrirank.mcdfbx.model.AppUser
import com.aistudio.santrirank.mcdfbx.model.NotaSantri
import com.aistudio.santrirank.mcdfbx.model.ObservacaoEquipe
import com.aistudio.santrirank.mcdfbx.model.SantriScoreCalculator
import com.aistudio.santrirank.mcdfbx.model.ScoringWeights
import com.aistudio.santrirank.mcdfbx.model.StatusAuditoria
import com.aistudio.santrirank.mcdfbx.model.SituacaoColaborador
import com.aistudio.santrirank.mcdfbx.model.TipoAcaoAuditoria
import com.aistudio.santrirank.mcdfbx.model.UsuarioRole
import com.aistudio.santrirank.mcdfbx.security.AuthSession
import com.aistudio.santrirank.mcdfbx.security.PasswordHasher
import com.aistudio.santrirank.mcdfbx.security.ReceiptIntegrityValidator
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AndroidAppBridge(
    private val context: Context,
    private val repository: SantriRepository,
    private val webView: WebView
) {
    private val authSession = AuthSession(context)
    private val gson = Gson()
    private val bootstrapScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val aiOcrService = SantriAiOcrService(context)
    private val pendingScanJobs = ConcurrentHashMap<String, Job>()
    private val pendingAiScans = ConcurrentHashMap<String, PendingAiScan>()

    private data class PendingAiScan(
        val employeeId: Long,
        val photoName: String,
        val photoSource: String,
        val observation: String,
        val noteNumber: String,
        val photoHash: String,
        val photoFile: File,
        val ocrResult: OcrScanResult,
        val weights: ScoringWeights,
        val itemWeightFactor: Double
    )

    @JavascriptInterface
    fun bootstrap() {
        bootstrapScope.launch {
            val result = loadBootstrapData()
            if (!webView.post {
                    webView.evaluateJavascript(
                        "window.applyNativeBootstrapResult(${JSONObject.quote(result)})",
                        null
                    )
                }
            ) {
                Log.w(TAG, "WebView is no longer available to receive app data.")
            }
        }
    }

    fun dispose() {
        bootstrapScope.cancel()
        pendingScanJobs.clear()
        pendingAiScans.values.forEach { it.photoFile.delete() }
        pendingAiScans.clear()
    }

    private suspend fun loadBootstrapData(): String {
        return try {
            if (!authSession.isAuthenticated()) {
                return gson.toJson(
                    mapOf(
                        "authenticated" to false,
                        "rememberedUsername" to rememberedUsername(),
                        "rememberCredentials" to authSession.shouldRememberCredentials(),
                        "rememberedPassword" to authSession.rememberedPassword()
                    )
                )
            }
            repository.seedIfEmpty()
            val isAdmin = authSession.currentRole() == UsuarioRole.ADMIN
            val colaboradores = if (isAdmin) {
                repository.getAllColaboradores()
            } else {
                repository.getActiveColaboradores()
            }
            val notas = repository.getAllNotas()
            val observations = repository.getRecentObservacoes(50)
            val workspace = repository.getUserWorkspace()
            val adminUsers = if (isAdmin) {
                repository.getAllAppUsers()
                    .filter { it.role == UsuarioRole.ADMIN.name }
                    .map { mapOf("username" to it.username, "role" to it.role) }
            } else {
                emptyList()
            }
            val employees = colaboradores.map { colaborador ->
                val approvedNotes = notas.filter {
                    it.colaboradorId == colaborador.id &&
                        it.statusAuditoria == StatusAuditoria.APROVADO.name
                }
                val points = colaborador.pontosAjusteAdm +
                    approvedNotes.sumOf { it.pontuacaoTotal }
                val tier = when {
                    points >= 5000.0 -> "Mestre"
                    points >= 3000.0 -> "Ouro"
                    points >= 1500.0 -> "Prata"
                    else -> "Bronze"
                }
                mapOf(
                    "id" to colaborador.id.toString(),
                    "matricula" to colaborador.codigoSantri,
                    "nome" to colaborador.nome,
                    "cargo" to colaborador.cargo,
                    "setor" to colaborador.setor,
                    "situacao" to colaborador.situacao,
                    "ativo" to colaborador.ativo,
                    "tier" to tier,
                    "pontosSemana" to points,
                    "pontosAjusteAdm" to colaborador.pontosAjusteAdm,
                    "volumeM3" to approvedNotes.sumOf { it.volumeM3 },
                    "pesoKg" to 0,
                    "notas" to approvedNotes.size,
                    "metaPct" to if (workspace.metaSemanalUnidade > 0) {
                        (points / workspace.metaSemanalUnidade * 100).toInt().coerceIn(0, 100)
                    } else {
                        0
                    }
                )
            }
            val noteItems = notas.map { note ->
                note.toDisplayNote(
                    author = note.colaboradorNome,
                    title = note.numeroNotaSantri,
                    detail = note.observacaoQualidade.ifBlank { note.statusAuditoria },
                    includeDetails = true
                )
            }
            val auditStatus = notas.groupBy { it.colaboradorId.toString() }
                .mapValues { (_, employeeNotes) ->
                    employeeNotes.maxByOrNull { it.createdAt.time }?.statusAuditoria
                        ?.let { if (it == StatusAuditoria.APROVADO.name) "Aprovado" else "Pendente" }
                        ?: "Pendente"
                }

            gson.toJson(
                mapOf(
                    "employees" to employees,
                    "notes" to noteItems.sortedByDescending {
                        (it["createdAt"] as? Number)?.toLong() ?: 0L
                    },
                    "observations" to observations.map { observation ->
                        mapOf(
                            "id" to "observacao-${observation.id}",
                            "autor" to observation.autorNome,
                            "titulo" to observation.titulo,
                            "detalhe" to observation.detalhe,
                            "tempo" to formatTime(observation.createdAt),
                            "createdAt" to observation.createdAt.time
                        )
                    },
                    "auditStatus" to auditStatus,
                    "adminUsers" to adminUsers,
                    "weeklyGoal" to workspace.metaSemanalUnidade,
                    "authenticated" to authSession.isAuthenticated(),
                    "role" to authSession.currentRole()?.name
                )
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.e(TAG, "Failed to load app data.", error)
            gson.toJson(mapOf("error" to "Não foi possível carregar os dados do aplicativo."))
        }
    }

    @JavascriptInterface
    fun login(username: String, password: String) {
        bootstrapScope.launch(Dispatchers.IO) {
            val result = try {
                if (password.length > MAX_PASSWORD_LENGTH) {
                    mapOf("success" to false, "message" to "A senha pode ter no máximo 15 caracteres.")
                } else {
                    repository.seedIfEmpty()
                    when (val authResult = repository.authenticate(username.trim(), password)) {
                        is AuthResult.Success -> {
                            authSession.start(
                                authResult.username,
                                authResult.role,
                                "token_${System.currentTimeMillis()}"
                            )
                            mapOf("success" to true, "role" to authResult.role.name)
                        }
                        is AuthResult.Error -> mapOf("success" to false, "message" to authResult.message)
                        is AuthResult.Locked -> mapOf(
                            "success" to false,
                            "message" to "Conta bloqueada. Tente novamente em ${authResult.secondsRemaining}s."
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Login failed.", e)
                mapOf("success" to false, "message" to "Falha ao entrar. Tente novamente.")
            }
            val json = gson.toJson(result)
            val script = "window.onLoginResult(${JSONObject.quote(json)})"
            val posted = webView.post {
                webView.evaluateJavascript(script, null)
            }
            if (!posted) {
                Log.w(TAG, "WebView not available to receive login result")
            }
        }
    }

    @JavascriptInterface
    fun rememberUsername(username: String): String = runCatching {
        val cleanUsername = username.trim().take(MAX_USERNAME_LENGTH)
        if (cleanUsername.isBlank()) return failure("Informe um usuário para salvar.")
        context.getSharedPreferences(LOGIN_PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString(REMEMBERED_USERNAME_KEY, cleanUsername)
            .apply()
        success("Usuário salvo neste aparelho.")
    }.getOrElse { error ->
        Log.e(TAG, "Failed to save remembered username.", error)
        failure("Não foi possível salvar o usuário neste aparelho.")
    }

    private fun rememberedUsername(): String =
        context.getSharedPreferences(LOGIN_PREFERENCES, Context.MODE_PRIVATE)
            .getString(REMEMBERED_USERNAME_KEY, "")
            .orEmpty()

    @JavascriptInterface
    fun saveCredentials(username: String, password: String, remember: Boolean): String = runCatching {
        val cleanUsername = username.trim().take(MAX_USERNAME_LENGTH)
        if (cleanUsername.isBlank()) return failure("Informe o usuário.")
        if (remember) {
            authSession.rememberCredentials(cleanUsername, password.take(MAX_PASSWORD_LENGTH))
        } else {
            authSession.clearRememberedCredentials()
        }
        success("Preferência de acesso salva.")
    }.getOrElse { error ->
        Log.e(TAG, "Failed to save login credentials.", error)
        failure("Não foi possível salvar o acesso neste aparelho.")
    }

    @JavascriptInterface
    fun getAutoApproveNotes(): String {
        val enabled = context.getSharedPreferences(LOGIN_PREFERENCES, Context.MODE_PRIVATE)
            .getBoolean(AUTO_APPROVE_NOTES_KEY, false)
        return gson.toJson(mapOf("success" to true, "enabled" to enabled))
    }

    @JavascriptInterface
    fun setAutoApproveNotes(enabled: Boolean): String = runCatching {
        if (authSession.currentRole() != UsuarioRole.ADMIN) {
            failure("Somente o administrador pode alterar esta configuração.")
        } else {
            context.getSharedPreferences(LOGIN_PREFERENCES, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(AUTO_APPROVE_NOTES_KEY, enabled)
                .apply()
            success(if (enabled) "A aprovação automática foi ativada." else "A aprovação automática foi desativada.")
        }
    }.getOrElse { error ->
        Log.e(TAG, "Failed to update automatic note approval.", error)
        failure("Não foi possível alterar a aprovação automática.")
    }

    @JavascriptInterface
    fun logout() {
        authSession.clear()
    }

    @JavascriptInterface
    fun saveEmployee(
        employeeId: String,
        employeeCode: String,
        name: String,
        job: String,
        sector: String,
        situation: String,
        password: String,
        callback: String
    ) {
        bootstrapScope.launch(Dispatchers.IO) {
            val result = try {
                if (authSession.currentRole() != UsuarioRole.ADMIN) {
                    failure("Somente o administrador pode gerenciar funcionários.")
                } else {
                    val code = employeeCode.trim().uppercase(Locale.ROOT)
                    val cleanName = name.trim()
                    val cleanJob = job.trim()
                    val cleanSector = sector.trim()
                    if (code.isBlank() || cleanName.isBlank() || cleanJob.isBlank() || cleanSector.isBlank()) {
                        failure("Preencha matrícula, nome, cargo e setor.")
                    } else {
                        val selectedSituation = runCatching {
                            SituacaoColaborador.valueOf(situation)
                        }.getOrNull() ?: throw Exception("Selecione uma situação válida.")

                        val existing = employeeId.toLongOrNull()?.let { repository.getColaboradorById(it) }
                        if (employeeId.isNotBlank() && existing == null) {
                            throw Exception("Funcionário não encontrado.")
                        }
                        if (existing != null && existing.codigoSantri != code) {
                            throw Exception("A matrícula não pode ser alterada após o cadastro.")
                        }
                        val duplicate = repository.getColaboradorByCodigo(code)
                        if (duplicate != null && duplicate.id != existing?.id) {
                            throw Exception("Essa matrícula já está cadastrada.")
                        }
                        if (password.isNotBlank() && password.length !in MIN_PASSWORD_LENGTH..MAX_PASSWORD_LENGTH) {
                            throw Exception("A senha deve ter de 6 a 8 caracteres.")
                        }
                        if (existing == null && password.isBlank()) {
                            throw Exception("Informe uma senha inicial.")
                        }

                        val now = Date()
                        val newPasswordHash = if (password.isNotBlank()) PasswordHasher.hash(password)
                            else existing?.senhaHash.orEmpty()
                        val employee = (existing ?: com.aistudio.santrirank.mcdfbx.model.Colaborador()).copy(
                            userId = code,
                            nome = cleanName,
                            codigoSantri = code,
                            senhaHash = newPasswordHash,
                            cargo = cleanJob,
                            setor = cleanSector,
                            situacao = selectedSituation.name,
                            ativo = selectedSituation != SituacaoColaborador.AFASTADO,
                            updatedAt = now
                        )
                        val savedId = if (existing == null) {
                            repository.insertColaborador(employee.copy(createdAt = now))
                        } else {
                            repository.updateColaborador(employee)
                            existing.id
                        }
                        val storedUser = repository.findAppUser(code)
                        if (storedUser == null || password.isNotBlank()) {
                            repository.saveAppUser(
                                AppUser(
                                    username = code,
                                    passwordHash = newPasswordHash,
                                    role = UsuarioRole.OPERADOR.name
                                )
                            )
                        }
                        repository.insertAuditLog(
                            actor = authSession.currentUsername() ?: "sistema",
                            action = if (existing == null) TipoAcaoAuditoria.CADASTRO_COLABORADOR
                            else TipoAcaoAuditoria.EDICAO_COLABORADOR,
                            targetId = savedId,
                            previous = existing?.let { "${it.nome}|${it.cargo}|${it.setor}|${it.situacao}" }.orEmpty(),
                            novo = "$cleanName|$cleanJob|$cleanSector|${selectedSituation.name}",
                            reason = "Cadastro/edição pelo painel administrativo"
                        )
                        success(if (existing == null) "Funcionário cadastrado." else "Cadastro atualizado.")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save employee.", e)
                failure("Não foi possível salvar o cadastro do funcionário.")
            }
            val json = gson.toJson(result)
            webView.post {
                webView.evaluateJavascript("window.${callback}(${JSONObject.quote(json)})", null)
            }
        }
    }

    @JavascriptInterface
    fun createAdminUser(username: String, password: String, callback: String) {
        bootstrapScope.launch(Dispatchers.IO) {
            val result = try {
                if (authSession.currentRole() != UsuarioRole.ADMIN) {
                    failure("Somente o administrador pode criar outros acessos.")
                } else {
                    val cleanUsername = username.trim().uppercase(Locale.ROOT)
                    if (!ADMIN_USERNAME_REGEX.matches(cleanUsername)) {
                        failure("Use um usuário de 3 a 32 caracteres: letras, números, ponto, hífen ou sublinhado.")
                    } else if (password.length !in MIN_PASSWORD_LENGTH..MAX_PASSWORD_LENGTH) {
                        failure("A senha deve ter de 6 a 8 caracteres.")
                    } else {
                        val usernameExists = repository.getAllAppUsers()
                            .any { it.username.equals(cleanUsername, ignoreCase = true) }
                        val employeeCodeExists = repository.getAllColaboradores()
                            .any { it.codigoSantri.equals(cleanUsername, ignoreCase = true) }
                        if (usernameExists || employeeCodeExists) {
                            failure("Esse usuário já está cadastrado.")
                        } else {
                            val created = repository.createAppUserIfAbsent(
                                AppUser(
                                    username = cleanUsername,
                                    passwordHash = PasswordHasher.hash(password),
                                    role = UsuarioRole.ADMIN.name
                                )
                            )
                            if (!created) {
                                failure("Esse usuário já está cadastrado.")
                            } else {
                                repository.insertAuditLog(
                                    actor = authSession.currentUsername() ?: "sistema",
                                    action = TipoAcaoAuditoria.CRIACAO_USUARIO_ADMIN,
                                    targetId = 0,
                                    previous = "",
                                    novo = cleanUsername,
                                    reason = "Criação de acesso administrativo"
                                )
                                success("Acesso administrativo criado.")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create admin user.", e)
                failure("Não foi possível criar o acesso administrativo.")
            }
            val json = gson.toJson(result)
            webView.post {
                webView.evaluateJavascript("window.${callback}(${JSONObject.quote(json)})", null)
            }
        }
    }

    @JavascriptInterface
    fun setEmployeeActive(employeeId: String, active: Boolean, callback: String) {
        bootstrapScope.launch(Dispatchers.IO) {
            val result = try {
                if (authSession.currentRole() != UsuarioRole.ADMIN) {
                    failure("Somente o administrador pode gerenciar funcionários.")
                } else {
                    val employee = employeeId.toLongOrNull()?.let { repository.getColaboradorById(it) }
                        ?: throw Exception("Funcionário não encontrado.")
                    if (employee.codigoSantri == authSession.currentUsername() && !active) {
                        throw Exception("Não é possível desativar a própria conta.")
                    }
                    repository.updateColaborador(
                        employee.copy(
                            ativo = active,
                            situacao = if (active) SituacaoColaborador.ATIVO.name
                            else SituacaoColaborador.AFASTADO.name,
                            updatedAt = Date()
                        )
                    )
                    repository.insertAuditLog(
                        actor = authSession.currentUsername() ?: "sistema",
                        action = TipoAcaoAuditoria.REMOCAO_COLABORADOR,
                        targetId = employee.id,
                        previous = employee.situacao,
                        novo = if (active) SituacaoColaborador.ATIVO.name
                        else SituacaoColaborador.AFASTADO.name,
                        reason = if (active) "Reativação pelo painel administrativo"
                        else "Desativação lógica pelo painel administrativo"
                    )
                    success(if (active) "Funcionário reativado." else "Funcionário desativado.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update employee status.", e)
                failure("Não foi possível atualizar a situação do funcionário.")
            }
            val json = gson.toJson(result)
            webView.post {
                webView.evaluateJavascript("window.${callback}(${JSONObject.quote(json)})", null)
            }
        }
    }

    @JavascriptInterface
    fun saveManualNote(
        noteId: String,
        employeeId: String,
        noteNumber: String,
        itemCount: Int,
        unitCount: Double,
        volumeM3: Double,
        areaM2: Double,
        linearMeters: Double,
        bagCount: Double,
        executionMinutes: Int,
        errorCount: Int,
        observation: String,
        callback: String
    ) {
        bootstrapScope.launch(Dispatchers.IO) {
            val result = try {
                if (authSession.currentRole() != UsuarioRole.ADMIN) {
                    failure("Somente o administrador pode cadastrar ou editar notas.")
                } else {
                    val cleanNumber = noteNumber.trim()
                    if (cleanNumber.isBlank()) {
                        failure("Informe o número da nota.")
                    } else if (itemCount < 0 || executionMinutes < 0 || errorCount < 0 ||
                        listOf(unitCount, volumeM3, areaM2, linearMeters, bagCount)
                            .any { !it.isFinite() || it < 0.0 }
                    ) {
                        failure("Os valores da nota devem ser números iguais ou maiores que zero.")
                    } else {
                        val employee = employeeId.toLongOrNull()?.let { repository.getColaboradorById(it) }
                            ?.takeIf { it.ativo }
                            ?: throw Exception("Selecione um funcionário ativo.")
                        val existing = noteId.toLongOrNull()?.let { repository.getNotaById(it) }
                        if (noteId.isNotBlank() && existing == null) {
                            throw Exception("Nota não encontrada.")
                        }
                        if (existing?.statusAuditoria == StatusAuditoria.ESTORNADA.name) {
                            throw Exception("Uma nota estornada não pode ser editada.")
                        }
                        if (repository.isNotaDuplicata(cleanNumber, "", existing?.id)) {
                            throw Exception("Já existe uma nota com esse número.")
                        }
                        val itemWeightFactor = existing?.analiseIaJson
                            ?.takeIf { it.isNotBlank() }
                            ?.let { analysisJson ->
                                runCatching {
                                    gson.fromJson(analysisJson, AiAnalysisSnapshot::class.java)
                                        ?.itemWeightFactor
                                }.getOrNull()
                            } ?: 1.0
                        val score = SantriScoreCalculator.calculate(
                            qtdItens = itemCount,
                            qtdUnidades = unitCount,
                            volumeM3 = volumeM3,
                            areaM2 = areaM2,
                            metrosLineares = linearMeters,
                            qtdSacos = bagCount,
                            weights = repository.getScoringWeights(),
                            qtdErros = errorCount,
                            itemWeightFactor = itemWeightFactor
                        ).total
                        val now = Date()
                        val note = (existing ?: NotaSantri()).copy(
                            userId = employee.userId,
                            numeroNotaSantri = cleanNumber,
                            mesReferencia = SimpleDateFormat("MM/yyyy", Locale("pt", "BR")).format(now),
                            colaboradorId = employee.id,
                            colaboradorNome = employee.nome,
                            dataEmissao = existing?.dataEmissao ?: now,
                            qtdItens = itemCount,
                            qtdUnidades = unitCount,
                            volumeM3 = volumeM3,
                            areaM2 = areaM2,
                            metrosLineares = linearMeters,
                            qtdSacos = bagCount,
                            tempoExecucaoMin = executionMinutes,
                            qtdErros = errorCount,
                            observacaoQualidade = observation.trim(),
                            origemRegistro = existing?.origemRegistro ?: "CADASTRO_MANUAL",
                            pontuacaoTotal = score,
                            analiseIaJson = existing?.analiseIaJson.orEmpty(),
                            statusAuditoria = StatusAuditoria.PENDENTE.name,
                            createdAt = existing?.createdAt ?: now,
                            updatedAt = now
                        )
                        val savedId = if (existing == null) {
                            repository.insertNota(note)
                        } else {
                            repository.updateNota(note)
                            existing.id
                        }
                        repository.insertAuditLog(
                            actor = authSession.currentUsername() ?: "sistema",
                            action = TipoAcaoAuditoria.REVISAO_NOTA,
                            targetId = savedId,
                            previous = existing?.let {
                                "status=${it.statusAuditoria}; pontos=${it.pontuacaoTotal}; erros=${it.qtdErros}"
                            }.orEmpty(),
                            novo = "status=PENDENTE; pontos=$score",
                            reason = if (existing == null) "Cadastro manual de nota"
                            else "Nota editada e enviada para nova auditoria; erros=$errorCount"
                        )
                        success(if (existing == null) "Nota cadastrada e enviada para auditoria." else "Nota atualizada e enviada para auditoria.")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save manual note.", e)
                failure("Não foi possível salvar a nota.")
            }
            val json = gson.toJson(result)
            webView.post {
                webView.evaluateJavascript("window.${callback}(${JSONObject.quote(json)})", null)
            }
        }
    }

    @JavascriptInterface
    fun setNotaAuditStatus(noteId: String, status: String, callback: String) {
        bootstrapScope.launch(Dispatchers.IO) {
            val result = try {
                if (authSession.currentRole() != UsuarioRole.ADMIN) {
                    failure("Somente o administrador pode auditar notas.")
                } else {
                    val note = noteId.toLongOrNull()?.let { repository.getNotaById(it) }
                        ?: throw Exception("Nota não encontrada.")
                    val nextStatus = when (status) {
                        "Aprovado", StatusAuditoria.APROVADO.name -> StatusAuditoria.APROVADO
                        "Revisar", StatusAuditoria.REVISAR.name -> StatusAuditoria.REVISAR
                        "Pendente", StatusAuditoria.PENDENTE.name -> StatusAuditoria.PENDENTE
                        else -> throw Exception("Selecione um status válido.")
                    }
                    if (note.statusAuditoria == StatusAuditoria.ESTORNADA.name) {
                        throw Exception("Uma nota estornada não pode ser reativada.")
                    }
                    repository.updateNota(note.copy(statusAuditoria = nextStatus.name, updatedAt = Date()))
                    repository.insertAuditLog(
                        actor = authSession.currentUsername() ?: "sistema",
                        action = if (nextStatus == StatusAuditoria.APROVADO) {
                            TipoAcaoAuditoria.APROVACAO_NOTA
                        } else {
                            TipoAcaoAuditoria.REVISAO_NOTA
                        },
                        targetId = note.id,
                        previous = note.statusAuditoria,
                        novo = nextStatus.name,
                        reason = "Auditoria individual pelo painel administrativo"
                    )
                    success("Nota marcada como ${nextStatus.name.lowercase(Locale.ROOT)}.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update note audit status.", e)
                failure("Não foi possível atualizar a auditoria da nota.")
            }
            val json = gson.toJson(result)
            webView.post {
                webView.evaluateJavascript("window.${callback}(${JSONObject.quote(json)})", null)
            }
        }
    }

    @JavascriptInterface
    fun setNotaAuditStatusWithErrors(noteId: String, status: String, errorCount: Int, callback: String) {
        bootstrapScope.launch(Dispatchers.IO) {
            val result = try {
                if (authSession.currentRole() != UsuarioRole.ADMIN) {
                    failure("Somente o administrador pode auditar notas.")
                } else if (errorCount < 0) {
                    failure("Informe uma quantidade de erros igual ou maior que zero.")
                } else {
                    val note = noteId.toLongOrNull()?.let { repository.getNotaById(it) }
                        ?: throw Exception("Nota não encontrada.")
                    if (note.statusAuditoria == StatusAuditoria.ESTORNADA.name) {
                        throw Exception("Uma nota estornada não pode ser reativada.")
                    }
                    val nextStatus = when (status) {
                        "Aprovado", StatusAuditoria.APROVADO.name -> StatusAuditoria.APROVADO
                        "Revisar", StatusAuditoria.REVISAR.name -> StatusAuditoria.REVISAR
                        "Pendente", StatusAuditoria.PENDENTE.name -> StatusAuditoria.PENDENTE
                        else -> throw Exception("Selecione um status válido.")
                    }
                    val itemWeightFactor = note.analiseIaJson
                        .takeIf { it.isNotBlank() }
                        ?.let { analysisJson ->
                            runCatching {
                                gson.fromJson(analysisJson, AiAnalysisSnapshot::class.java)?.itemWeightFactor
                            }.getOrNull()
                        } ?: 1.0
                    val recalculated = SantriScoreCalculator.calculate(
                        qtdItens = note.qtdItens,
                        qtdUnidades = note.qtdUnidades,
                        volumeM3 = note.volumeM3,
                        areaM2 = note.areaM2,
                        metrosLineares = note.metrosLineares,
                        qtdSacos = note.qtdSacos,
                        weights = repository.getScoringWeights(),
                        qtdErros = errorCount,
                        itemWeightFactor = itemWeightFactor
                    ).total
                    val updated = note.copy(
                        qtdErros = errorCount,
                        pontuacaoTotal = recalculated,
                        statusAuditoria = nextStatus.name,
                        updatedAt = Date()
                    )
                    repository.updateNota(updated)
                    repository.insertAuditLog(
                        actor = authSession.currentUsername() ?: "sistema",
                        action = if (nextStatus == StatusAuditoria.APROVADO) {
                            TipoAcaoAuditoria.APROVACAO_NOTA
                        } else {
                            TipoAcaoAuditoria.REVISAO_NOTA
                        },
                        targetId = note.id,
                        previous = "status=${note.statusAuditoria}; erros=${note.qtdErros}; pontos=${note.pontuacaoTotal}",
                        novo = "status=${nextStatus.name}; erros=$errorCount; pontos=$recalculated",
                        reason = "Auditoria administrativa com registro de erros"
                    )
                    if (nextStatus == StatusAuditoria.APROVADO) {
                        success("Nota aprovada com ${recalculated.toInt()} pontos após ${errorCount} erro(s).")
                    } else {
                        success("Nota marcada como ${nextStatus.name.lowercase(Locale.ROOT)}.")
                    }
                }
            } catch (error: Exception) {
                Log.e(TAG, "Failed to audit note with errors.", error)
                failure("Não foi possível concluir a auditoria da nota.")
            }
            val json = gson.toJson(result)
            webView.post {
                webView.evaluateJavascript("window.${callback}(${JSONObject.quote(json)})", null)
            }
        }
    }

    @JavascriptInterface
    fun cancelNote(noteId: String, reason: String, callback: String) {
        bootstrapScope.launch(Dispatchers.IO) {
            val result = try {
                if (authSession.currentRole() != UsuarioRole.ADMIN) {
                    failure("Somente o administrador pode estornar notas.")
                } else if (reason.isBlank()) {
                    failure("Informe o motivo do estorno.")
                } else {
                    val note = noteId.toLongOrNull()?.let { repository.getNotaById(it) }
                        ?: throw Exception("Nota não encontrada.")
                    if (note.statusAuditoria == StatusAuditoria.ESTORNADA.name) {
                        throw Exception("Esta nota já foi estornada.")
                    }
                    repository.updateNota(
                        note.copy(
                            statusAuditoria = StatusAuditoria.ESTORNADA.name,
                            pontuacaoTotal = 0.0,
                            updatedAt = Date()
                        )
                    )
                    repository.insertAuditLog(
                        actor = authSession.currentUsername() ?: "sistema",
                        action = TipoAcaoAuditoria.REVISAO_NOTA,
                        targetId = note.id,
                        previous = "status=${note.statusAuditoria}; pontos=${note.pontuacaoTotal}",
                        novo = "status=ESTORNADA; pontos=0",
                        reason = reason.trim()
                    )
                    success("Nota estornada. Os pontos foram removidos do ranking.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to cancel note.", e)
                failure("Não foi possível estornar a nota.")
            }
            val json = gson.toJson(result)
            webView.post {
                webView.evaluateJavascript("window.${callback}(${JSONObject.quote(json)})", null)
            }
        }
    }

    @JavascriptInterface
    fun analyzeScan(
        employeeId: String,
        photoName: String,
        photoSource: String,
        observation: String,
        imageData: String,
        requestId: String
    ) {
        val job = bootstrapScope.launch(start = CoroutineStart.LAZY) {
            var stagedPhoto: File? = null
            var pendingToken: String? = null
            try {
                if (!authSession.isAuthenticated()) {
                    sendAiScanResult(requestId, failure("Entre na sua conta antes de analisar a nota."))
                    return@launch
                }
                val employee = employeeId.toLongOrNull()?.let { repository.getColaboradorById(it) }
                    ?.takeIf { it.ativo }
                    ?: run {
                        sendAiScanResult(requestId, failure("Selecione um funcionário ativo."))
                        return@launch
                    }
                if (!canSubmitForEmployee(employee.codigoSantri)) {
                    sendAiScanResult(requestId, failure("Você só pode enviar notas para o seu próprio usuário."))
                    return@launch
                }

                val dataUriHeader = imageData.substringBefore(',')
                val mimeType = IMAGE_DATA_URI_REGEX.matchEntire(dataUriHeader)
                    ?.groupValues?.get(1)
                    ?: run {
                        sendAiScanResult(requestId, failure("Selecione uma imagem JPEG, PNG ou WebP válida."))
                        return@launch
                    }
                val encodedImage = imageData.substringAfter(',', "")
                if (encodedImage.length > MAX_BASE64_IMAGE_LENGTH) {
                    sendAiScanResult(
                        requestId,
                        failure("A imagem excede o limite de 15 MB.")
                    )
                    return@launch
                }
                val imageBytes = Base64.decode(encodedImage, Base64.DEFAULT)
                if (imageBytes.isEmpty() || imageBytes.size > MAX_SCAN_IMAGE_BYTES) {
                    sendAiScanResult(
                        requestId,
                        failure("A imagem está vazia ou excede o limite de 15 MB.")
                    )
                    return@launch
                }

                val imageHash = ReceiptIntegrityValidator().hashImageBytes(imageBytes)
                val examples = repository.getAllNotas()
                    .asSequence()
                    .filter {
                        it.statusAuditoria == StatusAuditoria.APROVADO.name &&
                            it.analiseIaJson.isNotBlank()
                    }
                    .sortedByDescending { it.createdAt.time }
                    .mapNotNull { note ->
                        try {
                            gson.fromJson(note.analiseIaJson, AiAnalysisSnapshot::class.java)
                                ?.items?.take(2)
                        } catch (error: Exception) {
                            Log.w(TAG, "Could not read an approved AI analysis example.", error)
                            null
                        }
                    }
                    .flatten()
                    .take(MAX_LEARNING_EXAMPLES)
                    .map { item ->
                        AiLearningExample(item.description, item.category, item.complexity)
                    }
                    .toList()

                val scanResult = when (
                    val result = aiOcrService.analyzeImage(imageBytes, mimeType, examples)
                ) {
                    is OcrResult.Success -> result.result
                    is OcrResult.Failure -> {
                        sendAiScanResult(
                            requestId,
                            failure(result.exception.message ?: "Não foi possível analisar a nota.")
                        )
                        return@launch
                    }
                }
                val noteNumber = scanResult.numeroNota.ifBlank {
                    "IMG-${System.currentTimeMillis()}"
                }
                if (repository.isNotaDuplicata(noteNumber, imageHash)) {
                    sendAiScanResult(requestId, failure("Esta nota ou foto já foi registrada."))
                    return@launch
                }

                val scanToken = UUID.randomUUID().toString()
                pendingToken = scanToken
                val extension = when (mimeType) {
                    "image/png" -> "png"
                    "image/webp" -> "webp"
                    else -> "jpg"
                }
                stagedPhoto = File(context.cacheDir, "scan_preview_$scanToken.$extension")
                    .apply { writeBytes(imageBytes) }
                val weights = repository.getScoringWeights()
                val itemWeightFactor = calculateItemWeightFactor(scanResult.items, scanResult.qtdItens)
                val baseScore = calculateScanScore(scanResult, weights, itemWeightFactor = 1.0)
                val suggestedScore = calculateScanScore(scanResult, weights, itemWeightFactor)
                pendingAiScans[scanToken] = PendingAiScan(
                    employeeId = employee.id,
                    photoName = photoName.take(200),
                    photoSource = if (photoSource == "camera") "camera" else "anexo",
                    observation = observation.trim().take(500).ifBlank { photoName.take(200) },
                    noteNumber = noteNumber,
                    photoHash = imageHash,
                    photoFile = stagedPhoto,
                    ocrResult = scanResult,
                    weights = weights,
                    itemWeightFactor = itemWeightFactor
                )
                stagedPhoto = null
                val itemConfidence = scanResult.items.map { it.confidence }.average()
                sendAiScanResult(
                    requestId,
                    gson.toJson(
                        mapOf(
                            "success" to true,
                            "token" to scanToken,
                            "number" to noteNumber,
                            "itemCount" to scanResult.qtdItens,
                            "unitCount" to scanResult.qtdUnidades,
                            "volumeM3" to scanResult.volumeM3,
                            "areaM2" to scanResult.areaM2,
                            "linearMeters" to scanResult.metrosLineares,
                            "bagCount" to scanResult.qtdSacos,
                            "errorCount" to scanResult.qtdErros,
                            "basePoints" to baseScore.total,
                            "suggestedPoints" to suggestedScore.total,
                            "baseItemPoints" to baseScore.itensPts,
                            "suggestedItemPoints" to suggestedScore.itensPts,
                            "itemWeightFactor" to itemWeightFactor,
                            "confidence" to if (itemConfidence.isFinite()) itemConfidence else 0.0,
                            "items" to scanResult.items
                        )
                    )
                )
            } catch (error: CancellationException) {
                pendingToken?.let { pendingAiScans.remove(it)?.photoFile?.delete() }
                throw error
            } catch (error: Exception) {
                pendingToken?.let { pendingAiScans.remove(it)?.photoFile?.delete() }
                Log.e(TAG, "Failed to analyze uploaded note.", error)
                sendAiScanResult(
                    requestId,
                    failure("Não foi possível analisar a imagem. Tente novamente ou envie para revisão manual.")
                )
            } finally {
                stagedPhoto?.delete()
                kotlin.coroutines.coroutineContext[Job]?.let {
                    pendingScanJobs.remove(requestId, it)
                }
            }
        }
        pendingScanJobs.put(requestId, job)?.cancel()
        job.start()
    }

    @JavascriptInterface
    fun cancelScanAnalysis(requestIdOrToken: String) {
        pendingScanJobs.remove(requestIdOrToken)?.cancel()
        pendingAiScans.remove(requestIdOrToken)?.photoFile?.delete()
    }

    @JavascriptInterface
    fun confirmAnalyzedScan(scanToken: String, requestId: String) {
        val job = bootstrapScope.launch(start = CoroutineStart.LAZY) {
            try {
                sendAiScanResult(requestId, persistAnalyzedScan(scanToken))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.e(TAG, "Failed to save analyzed note.", error)
                sendAiScanResult(requestId, failure("Não foi possível enviar a nota analisada."))
            } finally {
                kotlin.coroutines.coroutineContext[Job]?.let {
                    pendingScanJobs.remove(requestId, it)
                }
            }
        }
        pendingScanJobs.put(requestId, job)?.cancel()
        job.start()
    }

    private suspend fun persistAnalyzedScan(scanToken: String): String {
        if (!authSession.isAuthenticated()) {
            return failure("Entre na sua conta antes de enviar a nota.")
        }
        val pending = pendingAiScans[scanToken]
            ?: return failure("A prévia expirou. Analise a imagem novamente.")
        val employee = repository.getColaboradorById(pending.employeeId)
            ?.takeIf { it.ativo }
            ?: return failure("O funcionário selecionado não está mais ativo.")
        if (!canSubmitForEmployee(employee.codigoSantri)) {
            return failure("Você só pode enviar notas para o seu próprio usuário.")
        }
        if (repository.isNotaDuplicata(pending.noteNumber, pending.photoHash)) {
            pendingAiScans.remove(scanToken)?.photoFile?.delete()
            return failure("Esta nota ou foto já foi registrada.")
        }

        val photoDirectory = File(context.filesDir, "notas").apply { mkdirs() }
        val photoFile = File(photoDirectory, "$scanToken.${pending.photoFile.extension}")
        val note = pending.ocrResult.toNotaSantri(
            colaboradorId = employee.id,
            colaboradorNome = employee.nome,
            fotoUri = photoFile.absolutePath,
            fotoHashSha256 = pending.photoHash,
            weights = pending.weights,
            itemWeightFactor = pending.itemWeightFactor,
            analysisJson = gson.toJson(
                AiAnalysisSnapshot(pending.itemWeightFactor, pending.ocrResult.items)
            )
        ).copy(
            userId = employee.userId,
            numeroNotaSantri = pending.noteNumber,
            mesReferencia = SimpleDateFormat("MM/yyyy", Locale("pt", "BR")).format(Date()),
            observacaoQualidade = pending.observation.ifBlank {
                pending.ocrResult.observacaoQualidade
            },
            origemRegistro = if (pending.photoSource == "camera") {
                "OCR_FOTO"
            } else {
                "ANEXO_GALERIA"
            }
        )
        pending.photoFile.copyTo(photoFile, overwrite = true)
        val autoApprove = context.getSharedPreferences(LOGIN_PREFERENCES, Context.MODE_PRIVATE)
            .getBoolean(AUTO_APPROVE_NOTES_KEY, false)
        val noteId = try {
            repository.insertNota(
                note.copy(
                    statusAuditoria = if (autoApprove) {
                        StatusAuditoria.APROVADO.name
                    } else {
                        StatusAuditoria.PENDENTE.name
                    }
                )
            )
        } catch (error: Exception) {
            photoFile.delete()
            throw error
        }
        pendingAiScans.remove(scanToken)?.photoFile?.delete()
        return try {
            repository.insertAuditLog(
                actor = authSession.currentUsername() ?: "sistema",
                action = TipoAcaoAuditoria.REVISAO_NOTA,
                targetId = noteId,
                previous = "",
                novo = "status=${if (autoApprove) "APROVADO" else "PENDENTE"}; pontos=${note.pontuacaoTotal}",
                reason = if (autoApprove) {
                    "Nota aprovada automaticamente pela configuração do administrador"
                } else {
                    "Nota enviada com prévia de pontuação da IA"
                }
            )
            if (autoApprove) {
                success(
                    "Prévia de ${note.pontuacaoTotal.toInt()} pontos confirmada e aprovada automaticamente."
                )
            } else {
                success(
                    "Prévia de ${note.pontuacaoTotal.toInt()} pontos confirmada. " +
                        "Nota enviada para aprovação do ADM."
                )
            }
        } catch (error: Exception) {
            Log.e(TAG, "Note saved but audit log could not be recorded.", error)
            failure("A nota foi salva, mas não foi possível registrar a auditoria. Avise o ADM e não reenvie.")
        }
    }

    @JavascriptInterface
    fun submitScan(employeeId: String, photoName: String, observation: String, imageData: String, callback: String) {
        bootstrapScope.launch(Dispatchers.IO) {
            val result = try {
                if (!authSession.isAuthenticated()) {
                    failure("Entre na sua conta antes de registrar uma nota.")
                } else {
                    val employee = repository.getColaboradorById(employeeId.toLong())
                        ?: throw Exception("Colaborador não encontrado.")
                    if (!employee.ativo || !canSubmitForEmployee(employee.codigoSantri)) {
                        throw Exception("Você só pode enviar notas para o seu próprio usuário.")
                    }
                    val encodedImage = imageData.substringAfter(',', imageData)
                    if (encodedImage.length > MAX_BASE64_IMAGE_LENGTH) {
                        throw Exception("A imagem excede o limite de 15 MB.")
                    }
                    val mimeType = IMAGE_DATA_URI_REGEX.matchEntire(imageData.substringBefore(','))
                        ?.groupValues?.get(1)
                        ?: throw Exception("Selecione uma imagem JPEG, PNG ou WebP válida.")
                    val imageBytes = Base64.decode(encodedImage, Base64.DEFAULT)
                    if (imageBytes.isEmpty() || imageBytes.size > MAX_SCAN_IMAGE_BYTES) {
                        throw Exception("A imagem está vazia ou excede o limite de 15 MB.")
                    }

                    val imageHash = ReceiptIntegrityValidator().hashImageBytes(imageBytes)
                    val noteNumber = "IMG-${System.currentTimeMillis()}"
                    if (repository.isNotaDuplicata(noteNumber, imageHash)) {
                        throw Exception("Esta foto já foi registrada.")
                    }

                    val extension = when (mimeType) {
                        "image/png" -> "png"
                        "image/webp" -> "webp"
                        else -> "jpg"
                    }
                    val photoFile = File(context.filesDir, "notas").apply { mkdirs() }
                        .resolve("${System.currentTimeMillis()}.$extension")
                    photoFile.writeBytes(imageBytes)

                    val now = Date()
                    val note = NotaSantri(
                        userId = employee.userId,
                        numeroNotaSantri = noteNumber,
                        mesReferencia = SimpleDateFormat("MM/yyyy", Locale("pt", "BR")).format(now),
                        colaboradorId = employee.id,
                        colaboradorNome = employee.nome,
                        dataEmissao = now,
                        observacaoQualidade = observation.ifBlank { photoName },
                        origemRegistro = "ANEXO_GALERIA",
                        pontuacaoTotal = 0.0,
                        fotoUri = photoFile.absolutePath,
                        fotoHashSha256 = imageHash,
                        statusAuditoria = StatusAuditoria.PENDENTE.name,
                        createdAt = now,
                        updatedAt = now
                    )
                    repository.insertNota(note)
                    success("Foto da nota salva para conferência do administrador.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save scanned note.", e)
                failure("Não foi possível salvar a foto. Tente novamente.")
            }
            val json = gson.toJson(result)
            webView.post {
                webView.evaluateJavascript("window.${callback}(${JSONObject.quote(json)})", null)
            }
        }
    }

    @JavascriptInterface
    fun addObservation(employeeId: String, title: String, callback: String) {
        bootstrapScope.launch(Dispatchers.IO) {
            val result = try {
                if (!authSession.isAuthenticated()) {
                    failure("Entre na sua conta antes de salvar uma observação.")
                } else {
                    val employee = repository.getColaboradorById(employeeId.toLong())
                        ?: throw Exception("Colaborador não encontrado.")
                    repository.insertObservacao(
                        ObservacaoEquipe(
                            colaboradorId = employee.id,
                            autorNome = authSession.currentUsername() ?: "Equipe Brasília",
                            titulo = title.trim(),
                            detalhe = "Registro operacional.",
                            createdAt = Date()
                        )
                    )
                    success("Observação salva.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save team observation.", e)
                failure("Não foi possível salvar a observação.")
            }
            val json = gson.toJson(result)
            webView.post {
                webView.evaluateJavascript("window.${callback}(${JSONObject.quote(json)})", null)
            }
        }
    }

    @JavascriptInterface
    fun setAuditStatus(employeeId: String, status: String, callback: String) {
        bootstrapScope.launch(Dispatchers.IO) {
            val result = try {
                if (authSession.currentRole() != UsuarioRole.ADMIN) {
                    failure("Somente o administrador pode alterar a auditoria.")
                } else {
                    val employeeNotes = repository.getNotasByColaborador(employeeId.toLong())
                    val note = employeeNotes.maxByOrNull { it.createdAt.time }
                        ?: throw Exception("Não há notas para este colaborador.")
                    val newStatus = if (status == "Aprovado") {
                        StatusAuditoria.APROVADO.name
                    } else {
                        StatusAuditoria.REVISAR.name
                    }
                    repository.updateNota(note.copy(statusAuditoria = newStatus, updatedAt = Date()))
                    success("Status da nota atualizado.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update note audit status.", e)
                failure("Não foi possível atualizar a auditoria.")
            }
            val json = gson.toJson(result)
            webView.post {
                webView.evaluateJavascript("window.${callback}(${JSONObject.quote(json)})", null)
            }
        }
    }

    @JavascriptInterface
    fun adjustBonus(employeeId: String, bonusPoints: Double, reason: String, callback: String) {
        bootstrapScope.launch(Dispatchers.IO) {
            val result = try {
                if (authSession.currentRole() != UsuarioRole.ADMIN) {
                    failure("Somente o administrador pode ajustar pontos.")
                } else if (!bonusPoints.isFinite() || bonusPoints == 0.0 ||
                    kotlin.math.abs(bonusPoints) > 10000.0
                ) {
                    failure("Informe um ajuste diferente de zero, de até 10.000 pontos.")
                } else if (reason.isBlank()) {
                    failure("Informe o motivo do ajuste.")
                } else {
                    val employee = repository.getColaboradorById(employeeId.toLong())
                        ?: throw Exception("Colaborador não encontrado.")
                    val newTotal = employee.pontosAjusteAdm + bonusPoints
                    if (!newTotal.isFinite()) throw Exception("O total de pontos informado é inválido.")
                    repository.updateColaborador(
                        employee.copy(
                            pontosAjusteAdm = newTotal,
                            updatedAt = Date()
                        )
                    )
                    repository.insertAuditLog(
                        actor = authSession.currentUsername() ?: "sistema",
                        action = if (bonusPoints > 0) TipoAcaoAuditoria.BONUS_RAPIDO
                        else TipoAcaoAuditoria.AJUSTE_PONTOS,
                        targetId = employee.id,
                        previous = employee.pontosAjusteAdm.toString(),
                        novo = newTotal.toString(),
                        reason = reason.trim().take(250)
                    )
                    success(
                        "Ajuste de ${if (bonusPoints > 0) "+" else ""}" +
                            "${bonusPoints} pontos aplicado a ${employee.nome}."
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to adjust employee points.", e)
                failure("Não foi possível ajustar os pontos.")
            }
            val json = gson.toJson(result)
            webView.post {
                webView.evaluateJavascript("window.${callback}(${JSONObject.quote(json)})", null)
            }
        }
    }

    @JavascriptInterface
    fun loadNotePhoto(noteId: String, requestId: String) {
        val job = bootstrapScope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            try {
                val result = try {
                    if (authSession.currentRole() != UsuarioRole.ADMIN) {
                        failure("Somente o administrador pode visualizar anexos.")
                    } else {
                        val note = noteId.toLongOrNull()?.let { repository.getNotaById(it) }
                        if (note == null) {
                            failure("Nota não encontrada.")
                        } else {
                            val notesDirectory = File(context.filesDir, "notas").canonicalFile
                            val photoFile = File(note.fotoUri).canonicalFile
                            if (!photoFile.path.startsWith(notesDirectory.path + File.separator) ||
                                !photoFile.isFile
                            ) {
                                failure("O arquivo anexado não está disponível.")
                            } else {
                                gson.toJson(
                                    mapOf("success" to true, "dataUrl" to createNotePhotoPreview(photoFile))
                                )
                            }
                        }
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Log.e(TAG, "Failed to load note photo for review.", error)
                    failure("Não foi possível carregar a imagem da nota.")
                }
                val script = "if (window.onNotePhotoResult) " +
                    "window.onNotePhotoResult(${JSONObject.quote(requestId)}, " +
                    "${JSONObject.quote(result)});"
                if (!webView.post { webView.evaluateJavascript(script, null) }) {
                    Log.w(TAG, "WebView is no longer available to receive note photo.")
                }
            } finally {
                kotlin.coroutines.coroutineContext[Job]?.let {
                    pendingScanJobs.remove("photo-$requestId", it)
                }
            }
        }
        pendingScanJobs.put("photo-$requestId", job)?.cancel()
        job.start()
    }

    private fun createNotePhotoPreview(file: File): String {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw IllegalStateException("O arquivo anexado não é uma imagem válida.")
        }
        var sampleSize = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sampleSize > MAX_NOTE_PREVIEW_DIMENSION) {
            sampleSize *= 2
        }
        val bitmap = BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sampleSize }
        ) ?: throw IllegalStateException("Não foi possível decodificar a imagem da nota.")
        val rgbBitmap = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.RGB_565)
        Canvas(rgbBitmap).apply {
            drawColor(Color.WHITE)
            drawBitmap(bitmap, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
        }
        bitmap.recycle()
        val output = java.io.ByteArrayOutputStream()
        val compressed = rgbBitmap.compress(Bitmap.CompressFormat.JPEG, 78, output)
        rgbBitmap.recycle()
        if (!compressed) throw IllegalStateException("Não foi possível preparar a imagem da nota.")
        return "data:image/jpeg;base64," +
            Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
    }

    @JavascriptInterface
    fun updateWeeklyGoal(goal: Double, callback: String) {
        bootstrapScope.launch(Dispatchers.IO) {
            val result = try {
                if (authSession.currentRole() != UsuarioRole.ADMIN) {
                    failure("Somente o administrador pode alterar a meta.")
                } else {
                    val workspace = repository.getUserWorkspace()
                    repository.saveUserWorkspace(workspace.copy(metaSemanalUnidade = goal.coerceAtLeast(500.0)))
                    success("Meta semanal atualizada.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update weekly goal.", e)
                failure("Não foi possível atualizar a meta.")
            }
            val json = gson.toJson(result)
            webView.post {
                webView.evaluateJavascript("window.${callback}(${JSONObject.quote(json)})", null)
            }
        }
    }

    @JavascriptInterface
    fun saveCsv(csvContent: String, callback: String) {
        bootstrapScope.launch(Dispatchers.IO) {
            val result = try {
                if (authSession.currentRole() != UsuarioRole.ADMIN) {
                    failure("Somente o administrador pode exportar relatórios.")
                } else {
                    val fileName = "relatorio_santrirank_${System.currentTimeMillis()}.csv"
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val values = ContentValues().apply {
                            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                            put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
                            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                        }
                        val uri = context.contentResolver.insert(
                            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                            values
                        ) ?: throw Exception("Não foi possível criar o arquivo CSV.")
                        context.contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use {
                            it.write(csvContent)
                        } ?: throw Exception("Não foi possível gravar o arquivo CSV.")
                        success("CSV salvo em Downloads/$fileName.")
                    } else {
                        val directory = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                            ?: throw Exception("A pasta de Downloads não está disponível.")
                        File(directory, fileName).writeText(csvContent, Charsets.UTF_8)
                        success("CSV salvo em ${File(directory, fileName).absolutePath}.")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to export CSV.", e)
                failure("Não foi possível exportar o CSV.")
            }
            val json = gson.toJson(result)
            webView.post {
                webView.evaluateJavascript("window.${callback}(${JSONObject.quote(json)})", null)
            }
        }
    }

    @JavascriptInterface
    fun printReport() {
        if (authSession.currentRole() != UsuarioRole.ADMIN) return
        val activity = context as? Activity ?: return
        activity.runOnUiThread {
            val printManager = activity.getSystemService(Context.PRINT_SERVICE) as PrintManager
            printManager.print(
                "Relatório SantriRank",
                webView.createPrintDocumentAdapter("Relatório SantriRank"),
                PrintAttributes.Builder().build()
            )
        }
    }

    private fun NotaSantri.toDisplayNote(
        author: String,
        title: String,
        detail: String,
        includeDetails: Boolean = false
    ): Map<String, Any> = buildMap {
        put("id", "nota-$id")
        put("noteId", id.toString())
        put("autor", author)
        put("titulo", title)
        put("detalhe", detail)
        put("tempo", formatTime(createdAt))
        put("createdAt", createdAt.time)
        if (includeDetails) {
            put("employeeId", colaboradorId.toString())
            put("status", statusAuditoria)
            put("points", pontuacaoTotal)
            put("volumeM3", volumeM3)
            put("itemCount", qtdItens)
            put("unitCount", qtdUnidades)
            put("areaM2", areaM2)
            put("linearMeters", metrosLineares)
            put("bagCount", qtdSacos)
            put("executionMinutes", tempoExecucaoMin)
            put("errorCount", qtdErros)
            put("observation", observacaoQualidade)
            if (analiseIaJson.isNotBlank()) put("aiAnalysis", analiseIaJson)
        }
    }

    private fun formatTime(date: Date): String =
        SimpleDateFormat("HH:mm", Locale("pt", "BR")).format(date)

    private fun canSubmitForEmployee(employeeCode: String): Boolean =
        authSession.currentRole() == UsuarioRole.ADMIN ||
            authSession.currentUsername()?.equals(employeeCode, ignoreCase = true) == true

    private fun calculateScanScore(
        scan: OcrScanResult,
        weights: ScoringWeights,
        itemWeightFactor: Double
    ) = SantriScoreCalculator.calculate(
        qtdItens = scan.qtdItens,
        qtdUnidades = scan.qtdUnidades,
        volumeM3 = scan.volumeM3,
        areaM2 = scan.areaM2,
        metrosLineares = scan.metrosLineares,
        qtdSacos = scan.qtdSacos,
        weights = weights,
        qtdErros = scan.qtdErros,
        itemWeightFactor = itemWeightFactor
    )

    private fun sendAiScanResult(requestId: String, resultJson: String) {
        val script = "if (window.onScanAnalysisResult) " +
            "window.onScanAnalysisResult(${JSONObject.quote(requestId)}, " +
            "${JSONObject.quote(resultJson)});"
        if (!webView.post { webView.evaluateJavascript(script, null) }) {
            Log.w(TAG, "WebView is no longer available to receive scan analysis.")
        }
    }

    private fun success(message: String): String =
        gson.toJson(mapOf("success" to true, "message" to message))

    private fun failure(message: String): String =
        gson.toJson(mapOf("success" to false, "message" to message))

    private companion object {
        const val TAG = "SantriRank"
        const val MAX_SCAN_IMAGE_BYTES = 15 * 1024 * 1024
        const val MAX_BASE64_IMAGE_LENGTH = MAX_SCAN_IMAGE_BYTES * 4 / 3 + 4
        const val MAX_LEARNING_EXAMPLES = 8
        const val MAX_NOTE_PREVIEW_DIMENSION = 1280
        const val MAX_PASSWORD_LENGTH = 15
        const val MIN_PASSWORD_LENGTH = 6
        const val MAX_ADMIN_USERNAME_LENGTH = 32
        const val MAX_USERNAME_LENGTH = 80
        const val LOGIN_PREFERENCES = "santrirank_login"
        const val REMEMBERED_USERNAME_KEY = "remembered_username"
        const val AUTO_APPROVE_NOTES_KEY = "auto_approve_notes"
        val ADMIN_USERNAME_REGEX = Regex("^[A-Z0-9][A-Z0-9._-]{2,${MAX_ADMIN_USERNAME_LENGTH - 1}}$")
        val IMAGE_DATA_URI_REGEX = Regex("^data:(image/(?:jpeg|png|webp));base64$")
    }
}

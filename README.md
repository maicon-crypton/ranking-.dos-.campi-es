# Ranking dos Campeões

Aplicativo Android da Madeireira Campeão da Construção para gestão de notas, pontuação e ranking de funcionários.

## Recursos

- Login por matrícula e senha
- Perfis automático de administrador e operador
- Ranking de produtividade
- Scanner com OCR pelo Firebase AI
- Gestão de equipe e observações
- Relatórios, exportação CSV/PDF e auditoria
- Banco local Room e sessão criptografada

## Executar

Requisitos:

- Android Studio
- JDK 17
- Android SDK 36

```powershell
.\gradlew.bat clean assembleDebug
```

O APK da versão atual está disponível em `releases/RankingDosCampeoes-v1.0.0.apk`.

## Instalação

No Android, transfira o APK para o aparelho, abra o arquivo e autorize a instalação de fontes desconhecidas apenas quando solicitado pelo sistema.

Em uma instalação nova, o acesso administrativo inicial é `ADM-001` com a senha `Campeao8`. A senha de contas já existentes não é alterada automaticamente.

SHA-256 do APK:

```text
BB55422803A3F454623CBC4113985D83E6C51B9825DB1131B790E6F83E63635E
```

## Estrutura

- `app/src/main/assets/`: interface web e recursos visuais
- `app/src/main/java/com/aistudio/santrirank/mcdfbx/`: código Android
- `ai/`: OCR e análise de documentos
- `data/`: banco Room e repositório
- `model/`: entidades e regras de pontuação
- `security/`: autenticação, hash e sessão
- `export/`: geração de relatórios

## Segurança

Senhas usam PBKDF2-HMAC-SHA256. A sessão usa armazenamento criptografado e há bloqueio temporário após tentativas inválidas.

Antes da distribuição, substitua as credenciais de demonstração e configure uma chave de assinatura release própria.

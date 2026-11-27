# Interpretador de Notas SantriRank

Aplicativo desktop independente para acompanhar uma pasta de exportação do Santri, interpretar notas em TXT/PDF, identificar o auxiliar e guardar lançamentos em uma fila SQLite local.

## Limite importante da integração

O aplicativo Android atual salva colaboradores, notas e pontuação somente no banco local Room. Ele não possui Firestore, API de sincronização ou serviço de rede para receber créditos do Windows. Por isso, esta versão **não envia nem credita pontos no Android**: mantém os lançamentos como pendentes e permite exportá-los em JSON. Não armazena credenciais Firebase nem marca um lançamento como sincronizado sem confirmação de um serviço real.

O monitoramento da pasta é pós-exportação. A observação da fila de impressão do Windows não consegue, por si só, ler o conteúdo de qualquer trabalho nem pausar a impressão antes de sair; essa integração depende de configuração específica do Santri/impressora e não é simulada aqui.

## Requisitos

- Windows 10/11
- Python 3.11+
- TXT exportado pelo Santri; PDF digital com camada de texto requer `pypdf`

## Instalação e execução

No PowerShell:

```powershell
cd interpretadordenotas
py -3.11 -m venv .venv
.\.venv\Scripts\Activate.ps1
python -m pip install -r requirements.txt
Copy-Item config\santri_config.example.json config\santri_config.json
```

Edite `config\santri_config.json`, principalmente `watch_folder`, e inicie:

```powershell
python main.py
```

O diretório configurado é monitorado para novos arquivos `.txt` e `.pdf`. Arquivos já presentes ao iniciar são ignorados para evitar créditos acidentais de documentos antigos. Configure os códigos e nomes em `employees` para habilitar a seleção rápida; sem essa lista, o campo aceita a matrícula digitada.

Os dados ficam em `%LOCALAPPDATA%\SantriRank\interpretador.sqlite3`. O botão de exportação grava os lançamentos pendentes em JSON. TXT com imagem/OCR não é reconhecido; PDF sem texto selecionável é recusado com mensagem explícita.

## Pontuação

Os pesos iniciais refletem os valores padrão de `ScoringWeights` no Android. O administrador pode alterá-los no app, então confira/sincronize os pesos em `scoring_weights` antes de usar. O desktop aplica os mesmos componentes: base, itens, unidades, m³, m², metros lineares, sacos, bônus de mix/carga, penalidades e arredondamento a duas casas.

Mapeamento de unidades do documento: `PC/UN` para unidades, `MT` para metros lineares, `M3` para volume, `M2` para área e `SC` para sacos. Cada linha reconhecida conta como um item/SKU. Outras unidades não são pontuadas automaticamente.

## Testes

```powershell
py -3.11 -m pip install pytest
py -3.11 -m pytest
```

Os testes cobrem o pedido `6.397.023`, itens em `MT` e `PC`, cálculo de pontos, associação de matrícula e prevenção de duplicidade/fila.

## Privacidade e operação

- O agente não captura teclas fora da própria janela.
- A fila local é idempotente por hash SHA-256 do documento e número normalizado do pedido.
- O conteúdo bruto da nota não é persistido; a fila guarda métricas, hash e identificação.
- Reimpressões duplicadas são registradas localmente e não criam um segundo lançamento.
- O botão **Adiar** guarda o documento sem matrícula para revisão posterior; ele não gera crédito.
- Até existir uma integração autenticada no Android, revise os JSONs exportados e não trate a fila local como ranking sincronizado.

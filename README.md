# Roku Painel ✋📺

Painel web que **rastreia a sua mão pela webcam** e transforma gestos em comandos
para o **Roku** — sem controle remoto na mão. O rastreamento roda 100% no
navegador (MediaPipe Hands com os arquivos servidos localmente) e o backend
apenas envia as teclas pelo protocolo oficial de controle do Roku (ECP).

```
webcam ──► MediaPipe Hands (navegador, offline)
              │ 21 landmarks
              ▼
        motor de gestos (gesture-engine.js)
              │ gesto estável + dwell (segurar para confirmar)
              ▼
        POST /api/roku/key  ──►  Roku ECP (http://IP:8060/keypress/Home)
```

## O que já funciona

- **Rastreamento em tempo real** com esqueleto de 21 pontos desenhado sobre o
  vídeo, FPS, latência e estado dos cinco dedos.
- **Reconhecimento de gestos**: mão aberta, punho, apontar, pinça, dois dedos,
  joinha para cima e para baixo — com estabilização entre quadros e *dwell*
  (segurar o gesto por um tempo para confirmar).
- **Direcional por posição**: com o dedo indicador apontando, a mão nas bordas
  do quadro vira Cima/Baixo/Esquerda/Direita; existe zona morta no centro.
- **Controle do Roku**: descoberta automática por SSDP, envio de teclas,
  listagem e abertura de apps, digitação de texto.
- **Modo simulado**: sem Roku (ou sem câmera) o painel continua funcional, com
  um aparelho e uma mão sintética — ótimo para demonstrar.
- **Painel de telemetria**: histórico de comandos, controle remoto virtual,
  atalhos de teclado, ajustes de tempo/confiança e mapeamento gesto → tecla.

## Como rodar

Requisito: Python 3.10+ (testado com 3.11).

```bash
./run.sh                 # cria .venv, instala dependências e sobe em :8000
# ou
python3 -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
python -m uvicorn server.main:app --host 0.0.0.0 --port 8000
```

Abra `http://localhost:8000`, clique em **Ativar câmera** (ou **Ver modo demo**).

> A câmera só é liberada em `localhost` ou em páginas **HTTPS**. Se o painel
> estiver dentro de um iframe que não permite câmera, use o botão de abrir em
> nova aba — o modo demo funciona em qualquer situação.

## Gestos padrão

| Gesto | Tecla do Roku | Ação |
| --- | --- | --- |
| 🖐️ Mão aberta | `Home` | Voltar para a tela inicial |
| ✊ Punho | `Back` | Voltar |
| 🤏 Pinça | `Select` | OK / selecionar |
| ✌️ Dois dedos | `Play` | Play / pause |
| 👍 Joinha para cima | `VolumeUp` | Aumentar volume |
| 👎 Joinha para baixo | `VolumeDown` | Diminuir volume |
| ☝️ Apontar + borda do quadro | `Up` / `Down` / `Left` / `Right` | Direcional |

Todos os mapeamentos (menos o direcional, que depende da posição) podem ser
trocados em **Ajustes › Mapeamento de gestos**.

Ajustes disponíveis: tempo de confirmação (dwell), repetição automática do
direcional, confiança mínima do detector, espelhamento da imagem e exibição do
esqueleto. Tudo fica salvo no `localStorage`.

### Atalhos de teclado

`↑ ↓ ← →` direcional · `Enter` OK · `Backspace` voltar · `H` início ·
`Espaço` play/pause · `+` / `-` volume · `M` mudo.

## Conectando ao Roku

1. No Roku: **Configurações › Sistema › Configurações avançadas › Controle por
   rede** → *Habilitado* (em alguns modelos: *Controle de rede*).
2. O painel busca o aparelho na rede local via **SSDP** e envia comandos para
   `http://IP:8060` (ECP, sem autenticação).
3. Se a descoberta falhar (redes com cliente isolado, VLANs, firewall), informe
   o IP manualmente em **Ajustes › IP do Roku** ou em **Conectar Roku**.

Sem aparelho encontrado, o painel entra em **modo simulado** — os comandos são
registrados no histórico e exibidos na TV virtual.

## API

| Método | Rota | Descrição |
| --- | --- | --- |
| `GET` | `/api/health` | Status do serviço |
| `GET` | `/api/state` | Estado do Roku + teclas aceitas |
| `POST` | `/api/roku/connect` | `{ "ip": "192.168.0.15", "mock": false }` |
| `POST` | `/api/roku/discover` | Descoberta SSDP |
| `POST` | `/api/roku/key` | `{ "key": "Home", "source": "gesture", "label": "open_palm" }` |
| `GET` | `/api/roku/apps` | Lista de apps instalados |
| `POST` | `/api/roku/app` | `{ "id": "837" }` abre um app |
| `POST` | `/api/roku/text` | Digita texto (`Lit_<char>`) |
| `WS` | `/ws` | Eventos em tempo real (teclas, conexão, erros) |

Documentação interativa em `/api/docs`.

## Estrutura

```
server/
  main.py      FastAPI: estáticos, API REST e WebSocket
  roku.py      cliente ECP + descoberta SSDP + aparelho simulado
public/
  index.html   painel
  css/styles.css
  js/app.js           interface, WebSocket, atalhos
  js/tracker.js       câmera + MediaPipe + overlay
  js/gesture-engine.js  análise dos 21 landmarks, gestos, dwell
  js/demo-hand.js     mão sintética do modo demo
  vendor/mediapipe/hands/  MediaPipe Hands (wasm + modelos, offline)
tests/
  gesture-engine.test.mjs  testes do motor de gestos (node --test)
  dom-smoke.mjs            interface completa em jsdom (npm run test:dom)
  test_roku.py             controlador, ECP e API (pytest)
```

## Testes

```bash
npm install            # apenas para os testes de front (jsdom)
npm test               # motor de gestos
npm run test:dom       # boot, histórico, controle remoto, pipeline de gesto

pip install -r requirements-dev.txt
pytest -q              # backend: Roku simulado, validação de teclas, API
```

## Privacidade

O vídeo **não sai do computador**: a inferência acontece no navegador e o
servidor recebe somente a tecla já decidida. Os modelos e o runtime do
MediaPipe estão versionados em `public/vendor/mediapipe/hands`, então o painel
funciona offline depois do primeiro carregamento.

## Limitações conhecidas

- Uma mão por vez (configurável no `HandTracker`, `maxHands`).
- O joinha depende da mão na vertical; a classificação assume o punho para baixo.
- O detector é sensível a contraluz; a confiança mínima pode ser ajustada.
- Roku exige o aparelho na mesma rede e "Controle por rede" habilitado.

# Mini Pots — rastreamento de mão

Jogo Android nativo em Kotlin. A câmera mostra o ambiente, uma mesa virtual é desenhada por cima e você mexe nos potinhos com a **própria mão**, rastreada pelo MediaPipe Hand Landmarker. Não há mais mapeamento de ambiente (ARCore): o app não procura planos, não precisa do Google Play Services para RA e funciona em qualquer Android 7.0+ com câmera.

A sessão é temporária; potes e objetos não são salvos entre execuções.

## Como jogar

1. Instale o APK e autorize a câmera. Apoie o celular ou segure-o com uma mão e mostre a outra para a câmera traseira, a uns 30–60 cm. O botão **Câmera ⟲** troca para a câmera frontal (imagem espelhada).
2. O esqueleto da mão aparece sobre a imagem. O cursor fica entre o polegar e o indicador; o círculo na mesa mostra onde ele aponta.
3. **Pinça** (encostar polegar e indicador) faz o papel do dedo na tela:
   - pinça rápida = toque;
   - segurar a pinça e mover = arrastar.
4. Em **+ Potinho**, faça a pinça sobre a mesa. Pinça rápida em um pote abre ou fecha a tampa (ou use **Tampa**).
5. Escolha **Bola**, **Cubo**, **Moeda**, **Estrela** ou **Pedra** e faça a pinça em um pote aberto para guardar, ou na mesa para deixar fora.
6. Segure a pinça em um objeto para carregá-lo; solte sobre um pote aberto para guardar. Segurar a pinça em um pote o arrasta.
7. **Mover pote**, **Cor**, **−**, **+**, **Girar**, **Retirar** e **Excluir** agem no objeto selecionado, ou no pote se nenhum objeto estiver selecionado. Até 6 potes, 12 objetos por pote.

Tocar na tela continua funcionando exatamente como a pinça.

No canto superior direito ficam os números de desempenho: `fps` da tela, `mão N Hz` (resultados de rastreamento por segundo) e o custo em milissegundos de cada quadro rastreado.

## Como o rastreamento foi pensado para celular fraco

- **A tela nunca espera a mão.** O rastreamento roda em uma thread própria, com prioridade abaixo da de desenho, e só publica o resultado mais recente. O desenho lê esse resultado sem bloquear. Se a inferência ficar lenta, cai o `Hz` da mão, não o `fps`.
- **Inferência na CPU (XNNPACK), GPU só para desenhar.** Em GPU fraca, dividir a GPU com o modelo derruba o fps.
- **Sempre o quadro mais novo.** A análise usa `STRATEGY_KEEP_ONLY_LATEST`: quadros nunca entram em fila, então o resultado nunca descreve uma imagem velha.
- **Modo VIDEO do MediaPipe.** O detector de palma só roda quando a mão é perdida; com a mão em cena roda apenas a rede de 21 pontos.
- **Uma mão, entrada de 640×480.** O MediaPipe recorta a mão para 224 px; mandar mais resolução custa tempo sem ganhar precisão.
- **Sem mão em cena, a busca cai para ~15 vezes por segundo**, para o aparelho não esquentar à toa. Com a mão rastreada, todos os quadros da câmera são usados.
- **Nada de previsão nem compensação de atraso.** Não há extrapolação de posição, nem janela de média, nem “unlag”. Os únicos tratamentos são:
  - filtro One Euro por ponto: tira o tremor com a mão parada e praticamente desliga quando ela se move (atraso abaixo de 8 ms em movimento rápido, coberto por teste);
  - histerese na pinça (fecha abaixo de 0,30 e abre acima de 0,45 do tamanho da mão, medido nos pontos 3D), para a pinça não piscar;
  - o desenho se aproxima do resultado mais novo em uma fração do intervalo entre resultados, só para o movimento não “pular” entre um resultado e outro.
- **Desenho leve:** OpenGL ES 2.0, iluminação por vértice, geometria em buffers na GPU, superfície limitada a 720 px de largura, nenhuma alocação por quadro.
- **APK só com `armeabi-v7a` e `arm64-v8a`.**

## Limites

A qualidade depende da luz: com pouca luz a câmera borra o movimento e o rastreamento piora. Como não há mapeamento de ambiente, a mesa é fixa em relação à tela, não a uma superfície real. O movimento dos objetos é uma aproximação simples com gravidade e quique; não há colisão entre objetos nem oclusão pela mão real.

O rastreamento não pôde ser testado em aparelho neste repositório: a compilação e os testes de unidade rodam no GitHub Actions, mas os números de fps e Hz precisam ser conferidos no seu celular (eles aparecem na tela).

## Compilação

Requisitos: JDK 17, Android SDK com plataforma 35 e acesso à internet. Na raiz:

```sh
./gradlew testDebugUnitTest assembleDebug
```

Na primeira compilação o Gradle baixa o modelo `hand_landmarker.task` (cerca de 7,8 MB) do repositório oficial do MediaPipe para `app/src/main/assets/` e confere o SHA-256. O arquivo não fica no Git.

O resultado é `app/build/outputs/apk/debug/app-debug.apk`, assinado com a chave de depuração do Android. O workflow **Android APK** roda os testes, compila e publica o artefato `mini-pots-ar-debug` no GitHub Actions. Um APK de depuração é instalável, mas não é uma versão de produção assinada.

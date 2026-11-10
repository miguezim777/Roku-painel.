# Mini Pots AR

Jogo Android nativo em Kotlin, ARCore e OpenGL ES 3.0. A câmera e os planos mostrados vêm do ARCore: não existe modo de mapeamento simulado. A sessão é temporária; potes e objetos não são salvos entre execuções.

## Como jogar

1. Instale o APK em um Android compatível com ARCore, com Google Play Services para RA atualizado. Autorize a câmera.
2. Aponte para um ambiente bem iluminado, movimentando o celular devagar. As áreas azuladas indicam planos rastreados. O app detecta planos horizontais e verticais; a colocação é restrita a superfícies horizontais voltadas para cima.
3. Em **+ Potinho**, toque em uma área detectada. Toque no pote ou use **Tampa** para abrir e fechar.
4. Selecione uma peça e toque em um pote aberto para soltá-la nele, ou em uma superfície para colocá-la fora. Toque em peças para selecioná-las; arraste uma peça até outro pote aberto ou uma superfície detectada. **Retirar** posiciona a peça selecionada ao lado do pote aberto.
5. **Mover pote** muda o anchor do pote selecionado para a superfície tocada. **Cor**, **−**, **+**, **Girar** e **Excluir** operam na peça selecionada, ou no pote se nenhuma peça estiver selecionada. Até 12 peças por pote.

Se o rastreamento estiver pausado, as interações espaciais são bloqueadas. A estabilidade e a detecção dependem do ARCore, da iluminação e da textura da superfície; não há garantia de alinhamento perfeito. O movimento das peças é uma aproximação simples com gravidade e quique, não um motor de física completo; colisões entre objetos, oclusão por objetos reais, sombras reais e persistência entre sessões não estão implementadas. Não há suporte a miniaturas de brinquedo. Os limites físicos e de interação podem variar entre aparelhos.

## Compilação

Requisitos: JDK 17, Android SDK com plataforma 35 e acesso aos repositórios Google/Maven. Na raiz:

```sh
./gradlew assembleDebug
```

O resultado é `app/build/outputs/apk/debug/app-debug.apk`, assinado com a chave de depuração do Android. O workflow **Android APK** também compila o projeto e publica o artefato `mini-pots-ar-debug` no GitHub Actions. Um APK de depuração é instalável, mas não é uma versão de produção assinada.

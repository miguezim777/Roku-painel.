# Coliseu VR — relatório

## Rodada de correção (depois do primeiro teste em aparelho)

Quem jogou relatou quatro defeitos. Esta rodada mexe só neles; o resto do jogo ficou como estava.

**Como foi conferido.** Não há aparelho deste lado. Foi escrito um teste (`PipelineTest`) que imita o caminho inteiro — mão real → câmera lenta (30, 15, 10 e 6 leituras por segundo), leituras que falham, mão que some quando vai rápido, mão pequena que a câmera julga mais longe → filtro da mão → física — e o jogo é rodado por dentro dele. Isso mostra o que as regras fazem com leituras ruins; **não mostra o que o seu celular entrega**. Nenhum dos quatro itens foi visto funcionando num aparelho.

### 1. Andar com as mãos (Gorilla Tag)

**Causa encontrada na simulação.** O jogo só deixava a mão segurar o chão se a tivesse visto *chegar* nele, vinda de fora. Com poucas leituras por segundo, a mão vai de "acima do chão" a "bem dentro do chão" de uma leitura para a outra; essa mão era recusada, e uma leitura recusada ainda apagava a lembrança de que ela esteve fora. Na versão entregue, na pior linha da simulação, 5 puxadas de 60 cm com rastreamento lento levavam o corpo 0,6 m, ou 0,6 m **para trás**; com 30 leituras por segundo andava-se quase 9 m, e por isso os testes antigos, feitos com leituras a cada quadro, passavam. Não sei qual era a taxa no seu aparelho nesta versão; na medição antiga que você mandou eram 6 Hz.

**O que mudou** (`GorillaBody`, em `VrMath.kt`):

- A mão vista já dentro do chão segura de onde está. O que ela fizer dali em diante move o corpo; o caminho por onde entrou, não.
- A mão solta quando a leitura mais nova já está 12 cm fora do chão, sem esperar a mão desenhada chegar lá.
- A velocidade com que o corpo sai ao soltar é a média de um intervalo que cresce com o intervalo entre leituras (1,25 leitura, de 0,1 s a 0,5 s).
- A mão desenhada é empurrada para fora do chão, do muro e dos bonecos, em vez de afundar neles.
- Mão reencontrada (depois de sumir, ou em outro lugar) não puxa o corpo durante 0,1 s, enquanto vai para o lugar novo.

**Resultado simulado** (metros andados em 5 puxadas de 60 cm; primeiro valor com a mão sempre vista, segundo com o rastreador perdendo a mão sempre que ela anda mais de uma largura de mão entre duas fotos):

| mão afunda, mão menor | 30 Hz | 15 Hz | 10 Hz | 6 Hz |
|---|---|---|---|---|
| 6 cm, tamanho normal | 5,0 / 5,0 | 5,5 / 5,5 | 6,4 / 6,8 | 5,8 / 2,1 |
| 6 cm, 1,35× menor | 5,2 / 5,2 | 8,0 / 8,0 | 9,3 / 9,5 | 7,2 / 3,1 |
| 30 cm, tamanho normal | 5,8 / 5,8 | 6,6 / 7,6 | 7,2 / 7,8 | 6,4 / 1,9 |
| 30 cm, 1,35× menor | 7,0 / 7,0 | 7,9 / 7,0 | 7,9 / 4,3 | 5,4 / 2,7 |

**O que ainda está ruim na simulação.** A distância por puxada varia de 1,9 a 9,5 m para o mesmo gesto. O corpo sobe enquanto anda (olhos a 0,9–1,55 m, partindo de 0,8 m) e volta ao chão depois. A 6 Hz com a mão sumindo quando rápida, anda-se pouco.

**Se ainda não andar no aparelho**, a linha de medições agora começa por `chão E … D …`, a altura de cada mão sobre o chão. Se ela nunca chega perto de zero, o chão está fora de alcance (baixe **Altura**, aumente **Mãos**); se mostra `toca` e o corpo não sai do lugar, o defeito é outro e preciso das medições para achar.

### 2. Mãos que somem

- A mão perdida fica no lugar por 1,5 s (antes 0,9 s), e em celular lento por 8 leituras, até 3 s (antes 5 leituras, até 2 s). Continua ficando cinza aos poucos, não segura o chão e não pega nada nesse tempo.
- A câmera é pedida a 30 quadros por segundo fixos. Deixada por conta própria, num cômodo pouco iluminado ela demora até 1/10 s em cada foto, e a mão em movimento sai borrada — que é quando o rastreador a perde. **Isto é uma suposição sobre a causa**: não vi as fotos do seu aparelho. Se o aparelho não oferecer taxa fixa, nada muda.
- Mão reencontrada vai para o lugar novo sem empurrar o corpo.
- Simulado: com metade das leituras perdidas a 10 Hz, a mão não some nem pula mais de 10 cm por quadro; fora da imagem por 1 s, continua desenhada e volta sem mover o corpo.

Não mudei os limiares de confiança do MediaPipe (0,5 para achar, 0,35 para seguir): sem imagens do aparelho não há como saber se baixá-los traria mãos de volta ou mãos falsas.

### 3. Objetos pegos de longe

A regra antiga aceitava a mão a 34 cm do cabo e era generosa ao longo da linha de visão (porque a distância da câmera é ruim), valia para qualquer mão desenhada, e a mão do jogo podia ir tão longe quanto a câmera dissesse. Agora (`Coliseum`, em `ColiseumMath.kt`):

- **Limite do braço:** a mão do jogo fica a no máximo 1,6 m do ombro (com **Mãos** em 2,5); se a câmera pedir mais, ela é trazida de volta na mesma direção.
- **Distância real:** a palma tem de estar a menos de 20 cm do cabo, medidos no mundo do jogo, em qualquer direção.
- **Caminho livre:** dos olhos ao cabo não pode haver boneco nem muro.
- **Confiança:** só mão seguida de fato (verde), com confiança de 0,6 ou mais, por 0,12 s seguidos. Mão mantida, recém-achada ou reencontrada não pega.
- Do portão, a espada (a 2 m) não pode ser pega por nenhuma mão que a câmera consiga ver, mesmo julgada 2× mais longe (testado). É preciso andar até ela.

Os golpes já usavam a posição 3D da arma contra o boneco e não mudaram.

### 4. Travamentos

Aqui é onde menos posso afirmar: **não medi nada**, e o que travava (a tela, ou as mãos atrasadas em relação à tela) não dá para saber sem as medições. O que foi feito:

- **Mãos: uma (rápido).** Botão novo. Com duas mãos, quando só uma aparece o rastreador procura a outra de tempos em tempos, e essa procura é a parte cara. Com uma, nunca procura. No computador, o modelo leve custou 8,5 ms por quadro com uma mão e 15,6 a 27,4 ms com duas pedidas.
- **Desenho menor quando a tela não acompanha.** Abaixo de 45 quadros por segundo por 4 s seguidos, ou com aviso de calor forte do sistema, a largura do desenho cai um degrau (1280 → 1120 → 960).
- **Câmera a 30 quadros fixos** (item 2): também reduz o atraso entre a foto e a tela quando a luz é pouca.

O que já existia e continua: só o quadro mais novo da câmera é processado (os velhos são descartados), modelo leve, física em passo fixo, a arena em uma chamada de desenho por olho.

O que **não** foi feito: reduzir triângulos ou chamadas de desenho das mãos e armas; trocar o rastreador por outro; baixar a resolução da câmera (640×480 já é a menor útil para duas mãos).

### Testes

133 testes no GitHub Actions, nenhuma falha (eram 119). Novos: 7 em `LocomotionTest` (mão já dentro do chão, soltar pela leitura nova, limite do braço, pegar só no cabo, confiança, nada através de boneco ou muro) e 7 em `PipelineTest`.

## Depois das primeiras medições do aparelho

Chegaram 28 segundos de medições de um Xiaomi 25062RN2DL (Android 16), os primeiros depois de abrir o jogo; o resto do texto foi cortado ao colar. Nenhuma mão aparece nesses segundos (`mãos 0`, `chão E – D –`), então **eles não dizem nada sobre andar, mãos que somem ou alcance**. O que dizem:

| Medido | Valor | O que significa |
|---|---|---|
| Olhar | bússola | o aparelho **não tem giroscópio** |
| tela | 60 qps, 0 a 2 quadros lentos por segundo | o desenho não é o gargalo; o tempo de 14,8 ms inclui a espera pela tela (no primeiro segundo, a 108 qps, eram 6,5 ms) |
| rastreio sem mão | 9 a 12 por segundo | ou cada procura por mãos leva perto de 90 ms (o mais provável num aparelho desta classe), ou a câmera só faz umas 11 fotos por segundo; estas medições não separam as duas coisas, as novas separam |
| atraso | 161 a 184 ms | idade da foto mais o custo da procura |
| freio térmico | 0, bateria a 36 °C | o aparelho não estava quente |

O que **não** dá para saber por eles: quantas leituras por segundo há com uma mão seguida, se a câmera faz 30 fotos por segundo ou menos (o número `câmera` media as fotos que chegavam ao rastreador, não as que a câmera fazia), e se o jogo acha as mãos neste aparelho.

### O que mudou por causa disso

- **Vista atrasada sem giroscópio.** No modo bússola a vista passava por um alisamento fixo de 0,25 s: a imagem chegava um quarto de segundo depois da cabeça em todo giro. Isso é do código, não uma suposição. Agora (`HeadFilter`) o alisamento é o mesmo com a cabeça parada e cede quando ela gira. Simulado, com leituras que tremem 1,5°: a vista fica uns 3° atrás da cabeça entre 30 e 90° por segundo (antes 7,5° a 22,5°), e treme o mesmo com a cabeça parada (0,33° contra 0,30°). **Risco que não sei medir daqui:** ao puxar o chão, o corpo balança, o acelerômetro sente, e a vista pode balançar mais do que antes. Por isso há o botão **Bússola: rápida / suave**; "suave" é o comportamento antigo. Se o sensor do aparelho tremer 3° em vez de 1,5°, a vista parada treme mais (1,0° contra 0,6° na simulação).
- **A procura da segunda mão não interrompe um movimento.** Com uma mão seguida e **Mãos: duas**, a outra era procurada a cada 0,35 s, e cada procura custa várias leituras da mão seguida — neste aparelho, se a busca custa mesmo uns 90 ms, algo como 0,1 s parado a cada 0,35 s. Uma mão rápida é a que se perde numa pausa dessas. Agora, enquanto a mão cruza a imagem a mais de 0,8 largura por segundo, a procura espera até 1 s. O custo: a segunda mão pode demorar até 1 s para aparecer se a primeira estiver em movimento.
- **Medições que sobrevivem ao corte.** **Copiar medições** começa por um resumo da sessão (tempo com mãos na tela, quantas vezes sumiram, quanto cada mão tocou o chão e a que distância chegou, metros andados, armas pegas, custo por foto com mão e por busca) e lista os segundos do mais novo para o mais antigo, cada um com o instante.
- **Números da câmera medidos onde acontecem.** `câmera` agora conta as fotos que a câmera faz; há também `exposição`, `luz` (claridade da imagem), `busca` (custo da procura) e `sensor do olhar`.
- **Imagem escura.** O pedido de 30 fotos fixas da rodada anterior pode deixar a imagem escura demais onde há pouca luz, e aí nenhuma mão é achada. Se a imagem ficar abaixo de 12% de claridade por 4 s, a câmera volta ao ritmo livre e o jogo avisa.

138 testes no GitHub Actions, nenhuma falha. Nada desta parte foi visto num aparelho.

### O que não foi feito, e por quê

- Trocar o detector de palma pelo "lite" (deixaria a procura mais rápida): sem saber se as mãos estão sendo achadas neste aparelho, trocar por um detector menos preciso é um tiro no escuro.
- Pedir 120 Hz à tela: o aparelho mostrou que consegue (108 qps no primeiro segundo), mas o desenho disputaria os dois núcleos rápidos com o rastreamento, que é o que está lento.

### O que preciso para continuar

Jogue alguns minutos tentando andar e pegar uma arma, toque em **Copiar medições** e cole. O resumo vem primeiro, então mesmo cortado o texto serve. As perguntas que ele responde: as mãos aparecem (`mãos na tela`)? Chegam ao chão (`mais perto N cm`, `tocou`)? O corpo anda (`andou`)? Quanto custa uma foto com mão? A câmera faz 30 fotos por segundo?

---

# Rodada anterior

Tudo abaixo foi verificado **no computador**: testes de unidade (no GitHub Actions) e imagens geradas pelo código de desenho do jogo com um substituto da GPU. **Nada foi executado num celular.**

## O que mudou

| Área | Antes | Agora |
|---|---|---|
| Passo da física | um passo por quadro, do tamanho do quadro | passos fixos de 1/120 s com acumulador, no máximo 8 por quadro |
| Caminho da mão | só a posição no fim do quadro | linha reta entre dois quadros, testada a cada passo |
| Modos de andar | só contato | contato e fluido, com botão; nunca os dois juntos |
| Mão mantida (âmbar) | movia o corpo como uma mão seguida | só é desenhada; não move nem segura |
| Leitura que pula | virava movimento | é recusada e contada (`saltos de mão`) |
| Mão que aparece dentro de uma coisa | prendia-se e empurrava o corpo para fora | não segura até sair |
| Colisão do corpo | uma esfera | peito (25 cm) e cabeça (17 cm) |
| Cabeça girando com leitura atrasada | a mão ia junto com a vista | a leitura é posta onde a cabeça estava quando a foto foi tirada |
| Altura dos olhos e alcance das mãos | fixos (80 cm e 2,5×) | ajustáveis e guardados |
| Medições na tela | qps, Hz, ms, mãos, CPU/GPU | mais quedas de quadro, memória, temperatura, freio térmico; botão para copiar |
| Ponto de estado da mão | âmbar quando perdida | verde seguida, âmbar mantida, vermelho perdida |

Arquivos: `VrMath.kt` (`GorillaBody`, `Locomotion`, `LookHistory`, `HandRig.accept`), `ColiseumMath.kt`, `ColiseumRenderer.kt`, `ColiseumDrawing.kt`, `ColiseumActivity.kt`, `LocomotionTest.kt` (novo).

## Os 15 cenários pedidos

"Simulado" quer dizer: as mãos são postas quadro a quadro onde um jogador as poria, com os defeitos de um rastreamento real, e o teste confere o que o corpo faz. Não é o mesmo que jogar.

| # | Cenário | Como foi verificado | Resultado |
|---|---|---|---|
| 1 | Parado | simulado, 10 s com tremor de 4 mm, nos dois modos | passou: o corpo não sai do lugar |
| 2 | Uma mão empurrando o chão | simulado, cada mão | passou: 60 cm de mão, 60 cm de corpo |
| 3 | Duas mãos | simulado | passou: mesma distância que uma mão, não o dobro |
| 4 | Para a frente | simulado, contato e fluido (3 remadas, mais de 2 m) | passou |
| 5 | Para o lado | simulado, nos dois modos | passou |
| 6 | Pulo com os braços | simulado, nos dois modos | passou: sobe mais de 50 cm e volta ao chão |
| 7 | Subir o muro | simulado, só no modo contato | passou: três puxadas, 3 m |
| 8 | Perda de uma mão | simulado no meio de uma puxada | passou: sem ganho de velocidade, a outra mão continua |
| 9 | Perda das duas | simulado no meio de uma puxada | passou: o corpo desliza e para |
| 10 | Recuperação | simulado: as mãos voltam 60 cm adiante | passou: o corpo não se move |
| 11 | Mudança rápida de luz | **não executado** (precisa de câmera) | — |
| 12 | Mãos rápidas | simulado: 6,5 m/s de mão; mão que cruza um boneco num quadro; leitura a 55 m/s | passou: dentro dos limites, sem atravessar, leitura recusada |
| 13 | Colisão com obstáculos | simulado: remar contra o muro e contra um boneco; cabeça na borda do muro | passou |
| 14 | Uso prolongado com calor | **não executado** (precisa de aparelho) | — |
| 15 | Reabrir a câmera após pausa | **não executado** (precisa de aparelho) | — |

Também simulado: a mesma sequência de mãos a 30, 60 e 90 quadros por segundo leva o corpo ao mesmo lugar (diferença menor que 6 cm); com a cabeça girando 35° para cada lado e leituras de 6 Hz atrasadas 200 ms, o erro médio de uma mão parada cai de mais de 4 cm para menos de 1,2 cm.

Total no GitHub Actions: 119 testes, nenhuma falha.

## O que não foi medido

FPS, latência, tempo de inferência, memória e quadros perdidos **não têm valor medido nesta rodada**: só um aparelho os dá. O app mostra todos na linha do alto, e o botão **Copiar medições** copia os últimos 3 minutos.

## O que não foi feito

- MediaPipe Pose Landmarker: não avaliado. Os braços continuam calculados a partir da mão.
- Reação ao calor: o app mostra a temperatura, mas não reduz nada sozinho.
- LOD e redução de chamadas de desenho: nada mudou (cerca de 177 chamadas e 21 mil triângulos por olho no desenho de teste).
- Paleta sugerida: só as cores de estado do rastreamento (#35D07F e #F05B54) foram adotadas.
- Os aplicativos de referência não estavam disponíveis; nada foi lido nem copiado deles.

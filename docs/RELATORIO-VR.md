# Coliseu VR — relatório desta rodada

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

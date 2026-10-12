# Rota Certa

Aplicativo Android para gravar, salvar e refazer rotas reais usando GPS.

## Objetivo

O usuário inicia uma gravação de rota, percorre o trajeto normalmente e encerra ao chegar ao destino. O app registra os pontos de GPS ao longo do caminho, desenha o percurso no mapa e salva a rota para consulta e repetição futura.

## MVP inicial

- Mapa dentro do aplicativo
- Botão **Iniciar rota**
- Gravação contínua da posição por GPS
- Linha do trajeto em tempo real no mapa
- Registro de data, duração e distância
- Identificação de ruas/pontos do percurso quando disponível
- Botão **Parar e salvar**
- Nome personalizado para a rota
- Lista de rotas salvas
- Tela para visualizar uma rota gravada
- Modo **Refazer rota**, mostrando o trajeto salvo e a posição atual

## Arquitetura proposta

- Android nativo em Kotlin
- Jetpack Compose para interface
- Serviço de localização em primeiro plano durante a gravação
- Fused Location Provider para pontos de GPS
- Banco local Room para rotas e pontos
- Google Maps SDK para visualização inicial do mapa e polylines
- Camada de geocodificação para nomes de ruas

## Fluxo principal

1. Abrir o aplicativo
2. Visualizar o mapa e localização atual
3. Tocar em **Iniciar rota**
4. O app começa a registrar o percurso
5. O trajeto aparece desenhado no mapa em tempo real
6. Tocar em **Parar** ao chegar ao destino
7. Dar um nome à rota e salvar
8. Posteriormente selecionar a rota e usar **Refazer rota**

## Status

Projeto iniciado em 12/10/2026.

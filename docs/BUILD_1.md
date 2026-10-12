# Rota Certa — Build 1

## Objetivo da primeira build

Criar a base funcional para gravar uma rota real com GPS e salvá-la localmente.

## Fluxo

1. Abrir o app.
2. Exibir mapa e posição atual.
3. Tocar em **INICIAR ROTA**.
4. Iniciar serviço de localização em primeiro plano.
5. Registrar pontos de GPS continuamente.
6. Desenhar uma polyline no mapa em tempo real.
7. Exibir tempo e distância da gravação.
8. Tocar em **PARAR**.
9. Informar um nome para a rota.
10. Salvar rota e pontos no banco local.
11. Exibir a rota na tela de rotas salvas.

## Dados de cada ponto

- latitude
- longitude
- timestamp
- accuracy
- speed
- bearing
- altitude, quando disponível

## Dados da rota

- id
- nome
- data/hora de início
- data/hora de fim
- duração
- distância total
- latitude/longitude inicial
- latitude/longitude final
- lista de pontos associada

## Tecnologia

- Kotlin
- Jetpack Compose
- MVVM
- FusedLocationProviderClient
- Foreground Service do tipo location
- Room Database
- Google Maps SDK inicialmente
- Polyline para desenhar o percurso

## Regras da gravação

- A rota só começa após ação explícita do usuário.
- O serviço continua registrando enquanto a gravação estiver ativa.
- Filtrar pontos com precisão muito ruim.
- Evitar pontos duplicados quando o aparelho estiver parado.
- Persistir pontos durante a gravação para reduzir risco de perda em caso de fechamento inesperado.
- Ao parar, encerrar o serviço e consolidar os dados da rota.

## Próximas builds

- nomes de ruas por geocodificação
- instruções para refazer a rota
- alerta de desvio do trajeto salvo
- favoritos
- categorias de rota, como ônibus, trabalho, escolar e pessoal
- exportação/importação da rota
- backup opcional em nuvem

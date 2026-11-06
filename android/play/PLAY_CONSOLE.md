# Play Console: respostas para configurar o ContasLT

Checklist na ordem da tela "Termine de configurar seu app". Marque conforme for preenchendo.

## 1. Informar o conteúdo do app

- [ ] **Política de privacidade:** `https://contaslt.web.app/privacidade`
- [ ] **Detalhes do login:** o app exige login. Informe uma conta só para o revisor (ex.: `revisor.contaslt@gmail.com`),
      com alguns lançamentos de exemplo. Não use a conta real.
- [ ] **Anúncios:** Não, o app não tem anúncios.
- [ ] **Classificação de conteúdo:**
  - E-mail: `halkam.dev@gmail.com`
  - Categoria: "Todos os outros tipos de app" (utilitário/produtividade)
  - Responder **Não** a tudo: violência, sexo, drogas, apostas, interação entre usuários, compartilhamento de
    localização, compras digitais. Resultado esperado: Livre.
  - "Conteúdo online / gerado por IA": **Não**. A leitura de comprovante só extrai os dados da foto do próprio
    usuário, não é conteúdo gerado para consumo.
  - Ao lançar assinatura: refazer o questionário com "compra de artigos digitais" = **Sim**.
- [ ] **Público-alvo:** somente **18 anos ou mais**; o app não é atraente para crianças.
- [ ] **Segurança dos dados:**
  - Coleta ou compartilha dados? **Sim**
  - Criptografados em trânsito? **Sim**
  - Usuário pode pedir exclusão? **Sim**: `https://contaslt.web.app/excluir-conta`
  - Tipos de dados (todos **não compartilhados**, **não efêmeros**, finalidade **Funcionalidade do app**):

    | Tipo | Obrigatório? | Finalidade extra |
    |---|---|---|
    | Informações pessoais › Nome | Obrigatório | Gerenciamento de conta |
    | Informações pessoais › Endereço de e-mail | Obrigatório | Gerenciamento de conta |
    | Informações financeiras › Outras informações financeiras (lançamentos, contas, cartões) | Obrigatório | |
    | Fotos e vídeos › Fotos (comprovantes) | Opcional | |

  - **Não** declarar as notificações dos bancos: são processadas só no aparelho, nunca enviadas (o Google não
    considera isso coleta).
  - Railway, Supabase e Firebase são provedores de serviço: não conta como compartilhamento.
- [ ] **Apps governamentais:** Não.
- [ ] **Recursos financeiros:** se houver opção de controle de gastos / orçamento / finanças pessoais, marcar essa;
      senão, "O app não oferece nenhum recurso financeiro". Sem empréstimo, pagamento, investimento ou cripto.
- [ ] **Saúde:** nenhum recurso de saúde.
- [ ] **Status do comerciante:** não é comerciante (o app não vende nada).

## 2. Categoria e contato

- [ ] Categoria: **Finanças**
- [ ] E-mail: `halkam.dev@gmail.com`
- [ ] Site: `https://contaslt.web.app`

## 3. Página "Detalhes do app"

- [ ] **Nome:** ContasLT
- [ ] **Descrição curta** (77/80 caracteres):

```
Controle de gastos com lançamentos automáticos das notificações do seu banco.
```

- [ ] **Descrição completa:**

```
O ContasLT organiza suas finanças pessoais sem planilha: contas, cartões, lançamentos, contas recorrentes e relatórios, tudo no celular e sincronizado com a versão web.

LANÇAMENTOS A PARTIR DAS NOTIFICAÇÕES DO BANCO
Libere o acesso às notificações e escolha os apps dos seus bancos. Compras, Pix e pagamentos viram sugestões na caixa "A confirmar", já com valor, data, conta, categoria e até o vencimento recorrente que aquele pagamento quita. Um toque confirma. A leitura acontece só no seu aparelho: o texto das notificações não é enviado a lugar nenhum.

TUDO QUE VOCÊ PRECISA PARA CONTROLAR O MÊS
• Saldo, receitas e despesas do mês e gastos por categoria
• Contas a vencer e vencidas, com contas recorrentes (aluguel, assinaturas, parcelas)
• Cartões de crédito com fatura por mês e pagamento da fatura
• Transferências entre contas
• Importação de extrato OFX e Excel, com categorização automática por regras
• Foto do cupom fiscal: os itens da compra são lidos e entram no lançamento
• Relatórios de gastos por categoria e por item

FUNCIONA OFFLINE
Seus dados ficam no aparelho e são sincronizados quando há internet. Dá para lançar no avião ou sem sinal.

PRIVACIDADE
Sem anúncios e sem rastreamento. Você pode excluir sua conta e todos os dados a qualquer momento, pelo app ou em contaslt.web.app/excluir-conta.
```

- [ ] **Ícone 512×512:** `android/play/icon-512.png`
- [ ] **Gráfico de destaque 1024×500:** `android/play/feature-graphic-1024x500.png`
- [ ] **Capturas de tela do celular** (1080×1920, 9:16, dados de exemplo): `android/play/screenshots/01..07-*.png`,
      nesta ordem: Início, histórico, A confirmar, Lançamentos, Recorrentes, itens do lançamento, gastos por categoria.
- [ ] **Capturas da versão para computador** (1920×1080, 16:9, PWA): `android/play/screenshots/desktop/01..05-*.png`.
- [ ] **Tablet 7"** (1080×1920, 600 dp): `android/play/screenshots/tablet-7/01..07-*.png`.
- [ ] **Tablet 10"** (1440×2560, 720 dp): `android/play/screenshots/tablet-10/01..07-*.png`.

## 4. Teste fechado (obrigatório antes da produção)

Conta de desenvolvedor pessoal nova: **12 testadores inscritos por 14 dias seguidos**.

- [ ] Criar um Grupo do Google (ex.: `contaslt-testers@googlegroups.com`) e usá-lo como lista de testadores
- [ ] Países e regiões: Brasil
- [ ] Versão: em *Teste interno › Promover versão › Teste fechado* (mesmo AAB, sem gerar outro)
- [ ] Enviar a versão para revisão
- [ ] Mandar o link de participação: cada pessoa aceita e instala o app
- [ ] Após 14 dias com 12+ inscritos: *Produção › Solicitar acesso de produção* (responder às perguntas sobre o teste)

## 5. Próximas versões

Cada envio precisa de um `versionCode` maior:

```bash
cd android
./gradlew bundleRelease -PversionCode=2 -PversionName=1.0.1
# app/build/outputs/bundle/release/app-release.aab
```

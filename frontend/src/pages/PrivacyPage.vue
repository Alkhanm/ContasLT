<script setup lang="ts">
/**
 * Política de privacidade (pública: exigida pela Play Store para dados financeiros e acesso a notificações).
 * Ao mudar o que o app coleta, atualize este texto e a data abaixo.
 */
const UPDATED = '6 de novembro de 2026'
// TODO: e-mail público de contato para assuntos de privacidade.
const CONTACT_EMAIL = 'CONTATO@EXEMPLO.COM'
</script>

<template>
  <div class="flex min-h-dvh items-start justify-center bg-background px-6 py-12">
    <article class="w-full max-w-2xl space-y-8 text-sm leading-relaxed">
      <div class="flex items-center gap-2.5">
        <img src="/favicon.svg" alt="" class="size-9 rounded-lg" />
        <span class="text-lg font-extrabold tracking-tight">ContasLT</span>
      </div>

      <header class="space-y-2">
        <h1 class="text-2xl font-extrabold tracking-tight">Política de privacidade</h1>
        <p class="text-muted-foreground">Atualizada em {{ UPDATED }}.</p>
        <p>
          O ContasLT é um app de controle de finanças pessoais, disponível na web e para Android. Esta política
          explica quais dados o app usa, onde eles ficam e como você pode apagá-los, nos termos da Lei Geral de
          Proteção de Dados (Lei 13.709/2018).
        </p>
      </header>

      <section class="space-y-2">
        <h2 class="text-base font-bold">Dados que você informa</h2>
        <ul class="list-disc space-y-1 pl-5">
          <li><strong>Cadastro:</strong> nome, e-mail e senha. A senha é guardada apenas como hash, nunca em texto.</li>
          <li>
            <strong>Suas finanças:</strong> contas, cartões, categorias, regras de categorização, lançamentos (com
            itens e observações), contas recorrentes, transferências e extratos que você importar.
          </li>
          <li><strong>Comprovantes:</strong> fotos de cupons e notas fiscais que você anexar aos lançamentos.</li>
        </ul>
        <p>
          Esses dados ficam guardados no seu aparelho e são enviados ao servidor do ContasLT só para fazer o backup e
          sincronizar entre os seus aparelhos e o navegador.
        </p>
      </section>

      <section class="space-y-2">
        <h2 class="text-base font-bold">Notificações dos apps de banco (Android)</h2>
        <p>
          Se você liberar o <strong>acesso a notificações</strong>, o app Android lê as notificações apenas dos apps de
          banco que você marcar na tela Notificações, para sugerir lançamentos (compras, Pix, pagamentos) na caixa
          <strong>A confirmar</strong>.
        </p>
        <ul class="list-disc space-y-1 pl-5">
          <li>A leitura é feita inteiramente no aparelho. O texto das notificações não é enviado ao servidor nem a ninguém.</li>
          <li>Notificações de outros apps são ignoradas e não são guardadas.</li>
          <li>
            Só vira dado sincronizado o lançamento que você confirmar, como se tivesse digitado à mão. As sugestões e
            as notificações não reconhecidas ficam apenas no aparelho e saem quando você descarta, limpa ou sai da conta.
          </li>
          <li>Você pode desligar o acesso a qualquer momento nas configurações do Android.</li>
        </ul>
      </section>

      <section class="space-y-2">
        <h2 class="text-base font-bold">Como os dados são usados</h2>
        <ul class="list-disc space-y-1 pl-5">
          <li>Para mostrar suas finanças, calcular saldos, faturas e relatórios e sincronizar seus aparelhos.</li>
          <li>
            Para ler as fotos de comprovantes que você enviar e preencher os itens do lançamento. A leitura é feita por
            um modelo de reconhecimento de imagem operado pelo ContasLT, e o resultado volta só para a sua conta.
          </li>
        </ul>
        <p>
          O ContasLT não vende, aluga nem compartilha seus dados para publicidade, não exibe anúncios e não usa
          ferramentas de rastreamento ou análise de uso.
        </p>
      </section>

      <section class="space-y-2">
        <h2 class="text-base font-bold">Onde os dados ficam</h2>
        <p>
          Os dados sincronizados e as fotos dos comprovantes ficam em provedores de infraestrutura contratados para
          hospedar o serviço: Railway (servidor), Supabase (banco de dados e armazenamento das fotos) e Firebase
          Hosting (site). Eles apenas armazenam e processam os dados em nome do ContasLT. Toda comunicação entre o app
          e o servidor é criptografada (HTTPS), e cada conta só acessa os próprios dados.
        </p>
      </section>

      <section class="space-y-2">
        <h2 class="text-base font-bold">Por quanto tempo</h2>
        <p>
          Enquanto a sua conta existir. Lançamentos e outros registros que você exclui deixam de aparecer e são
          marcados como excluídos para que a exclusão chegue aos seus outros aparelhos.
        </p>
      </section>

      <section class="space-y-2">
        <h2 class="text-base font-bold">Seus direitos e a exclusão da conta</h2>
        <p>
          Você pode ver e corrigir seus dados a qualquer momento pelo próprio app. Para
          apagar tudo, use <strong>Mais › Excluir minha conta</strong> no app ou a página
          <RouterLink :to="{ name: 'delete-account' }" class="font-semibold text-primary underline">Excluir minha conta</RouterLink>
          na web. A exclusão é imediata e apaga do servidor a conta, todos os dados sincronizados e as fotos dos
          comprovantes.
        </p>
      </section>

      <section class="space-y-2">
        <h2 class="text-base font-bold">Crianças</h2>
        <p>O ContasLT não é destinado a menores de 18 anos.</p>
      </section>

      <section class="space-y-2">
        <h2 class="text-base font-bold">Contato</h2>
        <p>
          Dúvidas ou pedidos sobre seus dados:
          <a :href="`mailto:${CONTACT_EMAIL}`" class="font-semibold text-primary underline">{{ CONTACT_EMAIL }}</a>.
          Mudanças nesta política serão publicadas nesta página, com a data de atualização.
        </p>
      </section>
    </article>
  </div>
</template>

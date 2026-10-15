<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { RouterLink, useRouter } from 'vue-router'
import { ChevronRight, LogOut, Monitor, Moon, Plus, Sun, Tag, Trash2, Wallet } from 'lucide-vue-next'
import { rulesApi } from '@/api'
import type { CategoryRule } from '@/api/types'
import CategoryIcon from '@/components/app/CategoryIcon.vue'
import CategorySelect from '@/components/app/CategorySelect.vue'
import SectionTitle from '@/components/app/SectionTitle.vue'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { notifyError, notifySuccess } from '@/lib/notify'
import { cn } from '@/lib/utils'
import { useAuthStore } from '@/stores/auth'
import { useCatalogStore } from '@/stores/catalog'
import { useThemeStore, type ThemePreference } from '@/stores/theme'

const auth = useAuthStore()
const catalog = useCatalogStore()
const theme = useThemeStore()
const router = useRouter()

const rules = ref<CategoryRule[]>([])
const keyword = ref('')
const ruleCategoryId = ref<number | null>(null)
const ruleType = ref<'EXPENSE' | 'INCOME'>('EXPENSE')
const saving = ref(false)

const themes: { value: ThemePreference; label: string; icon: typeof Sun }[] = [
  { value: 'light', label: 'Claro', icon: Sun },
  { value: 'dark', label: 'Escuro', icon: Moon },
  { value: 'system', label: 'Sistema', icon: Monitor },
]

onMounted(async () => {
  await catalog.load()
  loadRules()
})

async function loadRules() {
  try {
    rules.value = await rulesApi.list()
  } catch (err) {
    notifyError(err)
  }
}

async function addRule() {
  if (!keyword.value.trim() || !ruleCategoryId.value) return
  saving.value = true
  try {
    await rulesApi.create(keyword.value, ruleCategoryId.value)
    keyword.value = ''
    notifySuccess('Regra salva')
    loadRules()
  } catch (err) {
    notifyError(err)
  } finally {
    saving.value = false
  }
}

async function removeRule(r: CategoryRule) {
  try {
    await rulesApi.remove(r.id)
    rules.value = rules.value.filter((x) => x.id !== r.id)
  } catch (err) {
    notifyError(err)
  }
}

function logout() {
  auth.logout()
  catalog.reset()
  router.push({ name: 'login' })
}
</script>

<template>
  <div class="space-y-8">
    <section class="flex items-center gap-3 rounded-2xl border bg-card p-4">
      <span class="inline-flex size-12 items-center justify-center rounded-full bg-primary text-lg font-bold text-primary-foreground">
        {{ (auth.user?.name ?? '?').charAt(0).toUpperCase() }}
      </span>
      <span class="min-w-0 flex-1">
        <span class="block truncate font-bold">{{ auth.user?.name }}</span>
        <span class="block truncate text-sm text-muted-foreground">{{ auth.user?.email }}</span>
      </span>
      <Button variant="outline" size="sm" @click="logout"><LogOut /> Sair</Button>
    </section>

    <section>
      <SectionTitle title="Aparência" />
      <div role="radiogroup" class="grid grid-cols-3 gap-2">
        <button
          v-for="t in themes" :key="t.value" type="button" role="radio" :aria-checked="theme.preference === t.value"
          :class="cn('flex flex-col items-center gap-1.5 rounded-xl border bg-card px-3 py-3 text-sm font-semibold transition-colors',
            theme.preference === t.value ? 'border-primary text-primary ring-2 ring-primary/30' : 'text-muted-foreground hover:bg-accent')"
          @click="theme.set(t.value)"
        >
          <component :is="t.icon" class="size-5" />
          {{ t.label }}
        </button>
      </div>
    </section>

    <section class="md:hidden">
      <SectionTitle title="Organização" />
      <ul class="divide-y rounded-2xl border bg-card px-4">
        <li>
          <RouterLink :to="{ name: 'categories' }" class="flex items-center gap-3 py-3 font-semibold">
            <Tag class="size-[18px] text-muted-foreground" /> <span class="flex-1">Categorias</span> <ChevronRight class="size-4 text-muted-foreground" />
          </RouterLink>
        </li>
        <li>
          <RouterLink :to="{ name: 'accounts' }" class="flex items-center gap-3 py-3 font-semibold">
            <Wallet class="size-[18px] text-muted-foreground" /> <span class="flex-1">Contas</span> <ChevronRight class="size-4 text-muted-foreground" />
          </RouterLink>
        </li>
      </ul>
    </section>

    <section>
      <SectionTitle title="Regras de categorização" hint="Se a descrição contiver a palavra, a categoria é sugerida na importação." />
      <form class="grid gap-2 rounded-2xl border bg-card p-3 md:grid-cols-[1fr_auto_14rem_auto]" @submit.prevent="addRule">
        <Input v-model="keyword" placeholder="Palavra-chave, ex.: ifood" maxlength="120" />
        <div role="radiogroup" class="grid grid-cols-2 gap-1 rounded-lg bg-muted p-1">
          <button
            v-for="t in (['EXPENSE', 'INCOME'] as const)" :key="t" type="button" role="radio" :aria-checked="ruleType === t"
            :class="cn('h-7 rounded-md px-3 text-xs font-semibold', ruleType === t ? 'bg-card shadow-xs' : 'text-muted-foreground')"
            @click="ruleType = t; ruleCategoryId = null"
          >
            {{ t === 'EXPENSE' ? 'Despesa' : 'Receita' }}
          </button>
        </div>
        <CategorySelect v-model="ruleCategoryId" :type="ruleType" :allow-none="false" />
        <Button type="submit" :disabled="saving || !keyword.trim() || !ruleCategoryId"><Plus :stroke-width="2.5" /> Adicionar</Button>
      </form>
      <ul v-if="rules.length" class="mt-2 divide-y rounded-2xl border bg-card px-4">
        <li v-for="r in rules" :key="r.id" class="flex items-center gap-3 py-2.5 text-sm">
          <span class="min-w-0 flex-1 truncate font-semibold">“{{ r.keyword }}”</span>
          <span class="flex items-center gap-2 text-muted-foreground">
            <CategoryIcon :icon="r.categoryIcon" :color="r.categoryColor" size="sm" /> {{ r.categoryName }}
          </span>
          <Button variant="ghost" size="icon-sm" class="text-muted-foreground hover:text-destructive" title="Remover" aria-label="Remover" @click="removeRule(r)">
            <Trash2 />
          </Button>
        </li>
      </ul>
      <p v-else class="mt-2 text-sm text-muted-foreground">Nenhuma regra ainda. Elas também são criadas automaticamente quando você categoriza uma importação.</p>
    </section>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { GOOGLE_CLIENT_ID, renderGoogleButton } from '@/lib/google'

/** Botão oficial "Continuar com o Google". Some quando o app não tem VITE_GOOGLE_CLIENT_ID. */
const props = withDefaults(defineProps<{ text?: 'continue_with' | 'signin_with'; divider?: boolean }>(), { text: 'continue_with' })
const emit = defineEmits<{ token: [idToken: string] }>()

const el = ref<HTMLElement | null>(null)
const failed = ref('')

onMounted(async () => {
  if (!GOOGLE_CLIENT_ID || !el.value) return
  try {
    await renderGoogleButton(el.value, (t) => emit('token', t), props.text)
  } catch (err) {
    failed.value = err instanceof Error ? err.message : 'Login do Google indisponível'
  }
})
</script>

<template>
  <div v-if="GOOGLE_CLIENT_ID" class="w-full">
    <div v-if="divider" class="mb-4 flex items-center gap-3 text-xs text-muted-foreground">
      <span class="h-px flex-1 bg-border" />ou<span class="h-px flex-1 bg-border" />
    </div>
    <div ref="el" class="flex min-h-11 w-full justify-center" />
    <p v-if="failed" class="mt-1 text-center text-xs text-muted-foreground">{{ failed }}</p>
  </div>
</template>

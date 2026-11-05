import { ContasDb } from '@/data/db'
import { LocalStore } from '@/data/store'
import { createAccount, createCategory } from './catalog'
import { useStoreForTests } from './context'
import { invalidateEnsured } from './recurring'

/** Store novo, vazio, ligado aos serviços (IndexedDB falso do fake-indexeddb). Use em beforeEach. */
export async function freshStore(): Promise<LocalStore> {
  const s = new LocalStore(new ContasDb(`test-${Math.random()}`))
  await s.load()
  useStoreForTests(s)
  invalidateEnsured()
  return s
}

/** Conta corrente e duas categorias (despesa e receita) para os testes. */
export function basicCatalog() {
  const account = createAccount({ name: 'Conta', type: 'CHECKING', initialBalance: 0, color: '#1B5E3F' })
  const expense = createCategory({ name: 'Mercado', type: 'EXPENSE', color: '#C98A2B', icon: 'shopping-cart' })
  const income = createCategory({ name: 'Salário', type: 'INCOME', color: '#1B5E3F', icon: 'briefcase' })
  return { account, expense, income }
}

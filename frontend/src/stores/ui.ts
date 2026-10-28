import { ref } from 'vue'
import { defineStore } from 'pinia'
import type { Transaction, TransactionType } from '@/api/types'

export interface TransactionFormDefaults {
  type?: TransactionType
  dueDate?: string
  accountId?: number | null
}

/** Estado global de interface: formulário de lançamento e sinal de "dados mudaram". */
export const useUiStore = defineStore('ui', () => {
  const transactionFormOpen = ref(false)
  const editingTransaction = ref<Transaction | null>(null)
  const formDefaults = ref<TransactionFormDefaults>({})
  /** Incrementa a cada gravação; páginas observam para recarregar. */
  const dataVersion = ref(0)

  const SIDEBAR_KEY = 'contaslt.sidebar'
  const sidebarCollapsed = ref(readSidebar())

  function readSidebar() {
    try {
      return localStorage.getItem(SIDEBAR_KEY) === 'collapsed'
    } catch {
      return false
    }
  }

  function toggleSidebar() {
    sidebarCollapsed.value = !sidebarCollapsed.value
    try {
      localStorage.setItem(SIDEBAR_KEY, sidebarCollapsed.value ? 'collapsed' : 'open')
    } catch {
      // sem armazenamento: só não lembra a preferência
    }
  }

  function newTransaction(defaults: TransactionFormDefaults = {}) {
    editingTransaction.value = null
    formDefaults.value = defaults
    transactionFormOpen.value = true
  }

  function editTransaction(tx: Transaction) {
    editingTransaction.value = tx
    formDefaults.value = {}
    transactionFormOpen.value = true
  }

  function closeTransactionForm() {
    transactionFormOpen.value = false
  }

  function dataChanged() {
    dataVersion.value++
  }

  return {
    transactionFormOpen, editingTransaction, formDefaults, dataVersion, sidebarCollapsed,
    newTransaction, editTransaction, closeTransactionForm, dataChanged, toggleSidebar,
  }
})

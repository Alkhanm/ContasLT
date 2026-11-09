/**
 * Login com Google no PWA (Google Identity Services). O botão oficial do Google entrega um ID token que o backend
 * confere em /api/auth/google. Sem VITE_GOOGLE_CLIENT_ID o recurso fica escondido.
 */
export const GOOGLE_CLIENT_ID: string = import.meta.env.VITE_GOOGLE_CLIENT_ID ?? ''

interface GoogleId {
  initialize(config: { client_id: string; callback: (r: { credential: string }) => void; ux_mode?: 'popup'; use_fedcm_for_button?: boolean }): void
  renderButton(el: HTMLElement, options: Record<string, unknown>): void
  disableAutoSelect(): void
}

declare global {
  interface Window {
    google?: { accounts: { id: GoogleId } }
  }
}

let loading: Promise<GoogleId> | null = null

function loadGis(): Promise<GoogleId> {
  if (window.google?.accounts?.id) return Promise.resolve(window.google.accounts.id)
  loading ??= new Promise<GoogleId>((resolve, reject) => {
    const script = document.createElement('script')
    script.src = 'https://accounts.google.com/gsi/client'
    script.async = true
    script.onload = () => (window.google?.accounts?.id ? resolve(window.google.accounts.id) : reject(new Error('gis')))
    script.onerror = () => {
      loading = null
      reject(new Error('Não foi possível carregar o login do Google. Verifique sua internet.'))
    }
    document.head.appendChild(script)
  })
  return loading
}

/** Desenha o botão "Continuar com o Google" em `el`; `onToken` recebe o ID token a cada login. */
export async function renderGoogleButton(el: HTMLElement, onToken: (idToken: string) => void, text: 'continue_with' | 'signin_with' = 'continue_with') {
  const gis = await loadGis()
  gis.initialize({ client_id: GOOGLE_CLIENT_ID, callback: (r) => onToken(r.credential), ux_mode: 'popup', use_fedcm_for_button: true })
  gis.renderButton(el, {
    type: 'standard',
    theme: 'outline',
    size: 'large',
    text,
    shape: 'rectangular',
    logo_alignment: 'center',
    locale: 'pt-BR',
    width: Math.min(400, Math.max(200, Math.round(el.clientWidth || 320))),
  })
}

/** Ao sair: o Google não entra sozinho de novo na próxima visita. */
export function googleSignOut() {
  window.google?.accounts?.id?.disableAutoSelect()
}

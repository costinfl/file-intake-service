export type Mode = 'mock' | 'real'

export interface Settings {
  mode: Mode
  apiBaseUrl: string
  randomFailuresEnabled: boolean
}

const STORAGE_KEYS = {
  mode: 'fileintake.mode',
  apiBaseUrl: 'fileintake.apiBaseUrl',
  randomFailuresEnabled: 'fileintake.randomFailuresEnabled',
} as const

// Default with zero configuration is 'mock' — this is what makes the site work on GitHub
// Pages with nothing deployed.
const DEFAULT_SETTINGS: Settings = { mode: 'mock', apiBaseUrl: '', randomFailuresEnabled: false }

export function loadSettings(): Settings {
  if (typeof localStorage === 'undefined') {
    return DEFAULT_SETTINGS
  }
  return {
    mode: localStorage.getItem(STORAGE_KEYS.mode) === 'real' ? 'real' : 'mock',
    apiBaseUrl: localStorage.getItem(STORAGE_KEYS.apiBaseUrl) ?? '',
    randomFailuresEnabled: localStorage.getItem(STORAGE_KEYS.randomFailuresEnabled) === 'true',
  }
}

export function saveSettings(settings: Settings): void {
  localStorage.setItem(STORAGE_KEYS.mode, settings.mode)
  localStorage.setItem(STORAGE_KEYS.apiBaseUrl, settings.apiBaseUrl)
  localStorage.setItem(STORAGE_KEYS.randomFailuresEnabled, String(settings.randomFailuresEnabled))
}

// Reads ?mode=real&apiBaseUrl=... on first load so a pre-configured link can be shared, then
// persists it and strips the params from the URL — localStorage remains the source of truth
// on subsequent visits.
export function applyUrlOverridesOnce(): void {
  const url = new URL(window.location.href)
  const mode = url.searchParams.get('mode')
  const apiBaseUrl = url.searchParams.get('apiBaseUrl')
  if (mode === null && apiBaseUrl === null) {
    return
  }

  const current = loadSettings()
  saveSettings({
    ...current,
    mode: mode === 'real' ? 'real' : current.mode,
    apiBaseUrl: apiBaseUrl ?? current.apiBaseUrl,
  })

  url.searchParams.delete('mode')
  url.searchParams.delete('apiBaseUrl')
  window.history.replaceState({}, '', url.toString())
}

import { createContext, useCallback, useContext, useState, type ReactNode } from 'react'
import { loadSettings, saveSettings, type Settings } from './settings'

interface SettingsContextValue {
  settings: Settings
  setSettings: (next: Settings) => void
}

const SettingsContext = createContext<SettingsContextValue | null>(null)

export function SettingsProvider({ children }: { children: ReactNode }) {
  const [settings, setSettingsState] = useState<Settings>(() => loadSettings())

  const setSettings = useCallback((next: Settings) => {
    saveSettings(next)
    setSettingsState(next)
  }, [])

  return <SettingsContext.Provider value={{ settings, setSettings }}>{children}</SettingsContext.Provider>
}

export function useSettings(): SettingsContextValue {
  const context = useContext(SettingsContext)
  if (!context) {
    throw new Error('useSettings must be used within a SettingsProvider')
  }
  return context
}

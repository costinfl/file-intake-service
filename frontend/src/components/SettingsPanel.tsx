import { useState } from 'react'
import { useSettings } from '../state/SettingsContext'
import type { Mode } from '../state/settings'

export function SettingsPanel() {
  const { settings, setSettings } = useSettings()
  const [open, setOpen] = useState(false)
  const [mode, setMode] = useState<Mode>(settings.mode)
  const [apiBaseUrl, setApiBaseUrl] = useState(settings.apiBaseUrl)
  const [randomFailuresEnabled, setRandomFailuresEnabled] = useState(settings.randomFailuresEnabled)
  const [validationError, setValidationError] = useState<string | null>(null)

  const save = () => {
    if (mode === 'real' && apiBaseUrl.trim() === '') {
      setValidationError('API base URL is required for real mode')
      return
    }
    setValidationError(null)
    setSettings({ mode, apiBaseUrl: apiBaseUrl.trim(), randomFailuresEnabled })
    setOpen(false)
  }

  return (
    <div className="settings">
      <button type="button" className={`mode-badge mode-badge--${settings.mode}`} onClick={() => setOpen((prev) => !prev)}>
        {settings.mode === 'mock' ? 'MOCK MODE' : 'REAL MODE'} ⚙
      </button>
      {open && (
        <div className="settings__panel">
          <label>
            <input type="radio" name="mode" checked={mode === 'mock'} onChange={() => setMode('mock')} />
            Mock (demo, no backend needed)
          </label>
          <label>
            <input type="radio" name="mode" checked={mode === 'real'} onChange={() => setMode('real')} />
            Real backend
          </label>
          {mode === 'real' && (
            <label className="field">
              API base URL
              <input
                value={apiBaseUrl}
                onChange={(event) => setApiBaseUrl(event.target.value)}
                placeholder="https://your-backend.example.com"
              />
            </label>
          )}
          {mode === 'mock' && (
            <label>
              <input
                type="checkbox"
                checked={randomFailuresEnabled}
                onChange={(event) => setRandomFailuresEnabled(event.target.checked)}
              />
              Simulate random upload failures
            </label>
          )}
          {validationError && <p className="error-text">{validationError}</p>}
          <button type="button" onClick={save}>
            Save
          </button>
        </div>
      )}
    </div>
  )
}

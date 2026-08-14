import { useEffect } from 'react'
import { SettingsProvider } from './state/SettingsContext'
import { applyUrlOverridesOnce } from './state/settings'
import { useSubmissionFlow } from './state/useSubmissionFlow'
import { SettingsPanel } from './components/SettingsPanel'
import { CreateSubmissionForm } from './components/CreateSubmissionForm'
import { FileSlotList } from './components/FileSlotList'
import { SubmissionSummary } from './components/SubmissionSummary'
import { LoadSubmissionForm } from './components/LoadSubmissionForm'

function SubmissionApp() {
  const { state, create, retryFile, commit, loadExisting, reset } = useSubmissionFlow()

  useEffect(() => {
    applyUrlOverridesOnce()
  }, [])

  const showCreateForm = state.phase === 'idle' || state.phase === 'creating'

  return (
    <div className="app">
      <header className="app__header">
        <h1>File Intake Demo</h1>
        <SettingsPanel />
      </header>

      {state.error && showCreateForm && <p className="error-text">{state.error}</p>}

      {showCreateForm && <CreateSubmissionForm onCreate={create} disabled={state.phase === 'creating'} />}

      {!showCreateForm && (
        <>
          <FileSlotList files={state.files} onRetry={retryFile} />
          <SubmissionSummary state={state} onCommit={commit} />
          <button type="button" className="link-button" onClick={reset}>
            Start a new submission
          </button>
        </>
      )}

      {showCreateForm && <LoadSubmissionForm onLoad={loadExisting} disabled={state.phase === 'creating'} />}
    </div>
  )
}

export default function App() {
  return (
    <SettingsProvider>
      <SubmissionApp />
    </SettingsProvider>
  )
}

import { StatusBadge } from './StatusBadge'
import type { FlowState } from '../state/useSubmissionFlow'

interface SubmissionSummaryProps {
  state: FlowState
  onCommit: () => void
}

export function SubmissionSummary({ state, onCommit }: SubmissionSummaryProps) {
  if (!state.submissionId || !state.submissionStatus) {
    return null
  }

  const uploadedCount = state.files.filter((f) => f.status === 'UPLOADED').length
  const allUploaded = state.files.length > 0 && uploadedCount === state.files.length
  const canCommit = allUploaded && state.submissionStatus === 'PENDING' && state.phase !== 'committing'

  return (
    <section className="panel">
      <h2>3. Commit</h2>
      <p>
        Submission <code>{state.submissionId}</code>
      </p>
      <p>
        Status: <StatusBadge status={state.submissionStatus} />
      </p>
      <p>
        {uploadedCount}/{state.files.length} files uploaded
      </p>
      {state.expiresAt && state.submissionStatus === 'PENDING' && <p>Expires at {new Date(state.expiresAt).toLocaleString()}</p>}
      {state.committedAt && <p>Committed at {new Date(state.committedAt).toLocaleString()}</p>}
      {state.error && <p className="error-text">{state.error}</p>}
      <button type="button" disabled={!canCommit} onClick={onCommit}>
        {state.phase === 'committing' ? 'Committing…' : 'Commit Submission'}
      </button>
    </section>
  )
}

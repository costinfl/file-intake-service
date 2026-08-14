import { useState } from 'react'

interface LoadSubmissionFormProps {
  onLoad: (submissionId: string) => void
  disabled: boolean
}

export function LoadSubmissionForm({ onLoad, disabled }: LoadSubmissionFormProps) {
  const [submissionId, setSubmissionId] = useState('')

  return (
    <section className="panel panel--secondary">
      <h2>Resume an existing submission</h2>
      <label className="field">
        Submission ID
        <input value={submissionId} onChange={(event) => setSubmissionId(event.target.value)} disabled={disabled} />
      </label>
      <button
        type="button"
        disabled={disabled || submissionId.trim() === ''}
        onClick={() => onLoad(submissionId.trim())}
      >
        Load
      </button>
    </section>
  )
}

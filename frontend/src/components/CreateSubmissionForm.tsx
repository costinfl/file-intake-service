import { useState } from 'react'
import { FileDropzone } from './FileDropzone'

interface CreateSubmissionFormProps {
  onCreate: (ownerId: string, files: File[]) => void
  disabled: boolean
}

function fileCountHint(count: number): string {
  if (count === 10) {
    return '10/10 files selected'
  }
  if (count < 10) {
    return `${count}/10 files selected — need ${10 - count} more`
  }
  return `${count}/10 files selected — remove ${count - 10}`
}

export function CreateSubmissionForm({ onCreate, disabled }: CreateSubmissionFormProps) {
  const [ownerId, setOwnerId] = useState('demo-owner')
  const [files, setFiles] = useState<File[]>([])

  const canCreate = !disabled && ownerId.trim() !== '' && files.length === 10

  return (
    <section className="panel">
      <h2>1. Create submission</h2>
      <label className="field">
        Owner ID
        <input value={ownerId} onChange={(event) => setOwnerId(event.target.value)} disabled={disabled} />
      </label>
      <FileDropzone onFilesSelected={setFiles} />
      <p className="file-count">{fileCountHint(files.length)}</p>
      <p className="hint">Tip: name a file with "fail" in it (e.g. fail-me.pdf) to see the retry flow.</p>
      <button type="button" disabled={!canCreate} onClick={() => onCreate(ownerId, files)}>
        {disabled ? 'Creating…' : 'Create Submission'}
      </button>
    </section>
  )
}

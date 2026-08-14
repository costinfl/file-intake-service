import { StatusBadge } from './StatusBadge'
import type { FileSlot } from '../state/useSubmissionFlow'

interface FileSlotCardProps {
  slot: FileSlot
  onRetry: (fileId: string) => void
}

function formatBytes(bytes: number | null): string {
  if (bytes === null) {
    return '—'
  }
  if (bytes < 1024) {
    return `${bytes} B`
  }
  if (bytes < 1024 * 1024) {
    return `${(bytes / 1024).toFixed(1)} KB`
  }
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}

export function FileSlotCard({ slot, onRetry }: FileSlotCardProps) {
  return (
    <li className="file-slot">
      <div className="file-slot__header">
        <span className="file-slot__ordinal">#{slot.ordinal + 1}</span>
        <span className="file-slot__name">{slot.declaredName}</span>
        <StatusBadge status={slot.status} />
      </div>
      <div className="file-slot__meta">
        {formatBytes(slot.sizeBytes ?? slot.declaredSize)}
        {slot.crc32c ? ` · crc32c ${slot.crc32c}` : ''}
      </div>
      {slot.status === 'UPLOADING' && (
        <div className="progress-bar">
          <div className="progress-bar__fill" style={{ width: `${slot.progress}%` }} />
        </div>
      )}
      {slot.status === 'FAILED' && (
        <div className="file-slot__failure">
          {slot.errorDetail && <span className="file-slot__error">{slot.errorDetail}</span>}
          <button type="button" onClick={() => onRetry(slot.fileId)} disabled={!slot.localFile}>
            Retry
          </button>
        </div>
      )}
    </li>
  )
}

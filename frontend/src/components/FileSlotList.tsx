import { FileSlotCard } from './FileSlotCard'
import type { FileSlot } from '../state/useSubmissionFlow'

interface FileSlotListProps {
  files: FileSlot[]
  onRetry: (fileId: string) => void
}

export function FileSlotList({ files, onRetry }: FileSlotListProps) {
  return (
    <section className="panel">
      <h2>2. Upload files</h2>
      <ul className="file-slot-list">
        {files.map((slot) => (
          <FileSlotCard key={slot.fileId} slot={slot} onRetry={onRetry} />
        ))}
      </ul>
    </section>
  )
}

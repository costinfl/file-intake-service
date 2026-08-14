import type { FileStatus, SubmissionStatus } from '../types/api'

type Status = FileStatus | SubmissionStatus

const LABELS: Record<Status, string> = {
  PENDING: 'Pending',
  UPLOADING: 'Uploading',
  UPLOADED: 'Uploaded',
  FAILED: 'Failed',
  COMMITTED: 'Committed',
  EXPIRED: 'Expired',
}

export function StatusBadge({ status }: { status: Status }) {
  return <span className={`status-badge status-badge--${status.toLowerCase()}`}>{LABELS[status]}</span>
}

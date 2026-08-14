import type { FileStatus, SubmissionStatus } from '../types/api'

export interface MockFile {
  fileId: string
  ordinal: number
  declaredName: string
  declaredSize: number
  status: FileStatus
  sizeBytes: number | null
  crc32c: string | null
  completeAttempts: number
}

export interface MockSubmission {
  submissionId: string
  ownerId: string
  status: SubmissionStatus
  expiresAt: string
  committedAt: string | null
  files: MockFile[]
}

// Module-level singleton: survives for the page session, reset on reload. Acceptable for a
// demo — there is no persistence layer behind the mock backend.
const submissions = new Map<string, MockSubmission>()

export function genId(): string {
  return crypto.randomUUID()
}

export function getSubmission(submissionId: string): MockSubmission | undefined {
  return submissions.get(submissionId)
}

export function saveSubmission(submission: MockSubmission): void {
  submissions.set(submission.submissionId, submission)
}

export function resetStore(): void {
  submissions.clear()
}

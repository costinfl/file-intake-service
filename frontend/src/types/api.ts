// Mirrors the backend DTOs field-for-field (com.fileintake.submission.dto.*). Jackson
// serializes Java records by component name, so these types line up 1:1 with no mapping layer.

export interface DeclaredFile {
  declaredName: string
  declaredSize: number
}

export interface CreateSubmissionRequest {
  ownerId: string
  files: DeclaredFile[]
}

export interface FileRef {
  fileId: string
  ordinal: number
  declaredName: string
}

export interface CreateSubmissionResponse {
  submissionId: string
  expiresAt: string
  files: FileRef[]
}

export interface UploadCredentialsResponse {
  uploadUrl: string
  headers: Record<string, string>
  expiresAt: string
  contentLengthMin: number
  contentLengthMax: number
}

export type FileStatus = 'PENDING' | 'UPLOADING' | 'UPLOADED' | 'FAILED'
export type SubmissionStatus = 'PENDING' | 'COMMITTED' | 'EXPIRED' | 'FAILED'

export interface CompleteFileResponse {
  fileId: string
  status: FileStatus
  sizeBytes: number | null
  crc32c: string | null
}

export interface FileStatusView {
  fileId: string
  ordinal: number
  declaredName: string
  status: FileStatus
  sizeBytes: number | null
  crc32c: string | null
}

export interface SubmissionStatusResponse {
  submissionId: string
  status: SubmissionStatus
  expiresAt: string
  committedAt: string | null
  files: FileStatusView[]
}

export interface CommitResponse {
  submissionId: string
  status: SubmissionStatus
  committedAt: string
}

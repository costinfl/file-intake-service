import { ApiError, type FileIntakeApiClient } from '../api/client'
import type {
  CommitResponse,
  CompleteFileResponse,
  CreateSubmissionRequest,
  CreateSubmissionResponse,
  FileStatusView,
  SubmissionStatusResponse,
  UploadCredentialsResponse,
} from '../types/api'
import { genId, getSubmission, saveSubmission, type MockFile, type MockSubmission } from './store'
import { shouldFail } from './scenarios'

const SUBMISSION_TTL_MS = 24 * 60 * 60 * 1000
const CREDENTIAL_TTL_MS = 15 * 60 * 1000
const EXPECTED_FILE_COUNT = 10

export interface MockClientOptions {
  randomFailuresEnabled: () => boolean
}

// Implements the same FileIntakeApiClient contract as RealApiClient, replicating the real
// backend's exact rules from SubmissionService/SubmissionFileService so the demo genuinely
// exercises the same UI states a real backend would produce.
export class MockApiClient implements FileIntakeApiClient {
  private readonly randomFailuresEnabled: () => boolean

  constructor(options: MockClientOptions) {
    this.randomFailuresEnabled = options.randomFailuresEnabled
  }

  async createSubmission(request: CreateSubmissionRequest): Promise<CreateSubmissionResponse> {
    if (!request.ownerId || request.ownerId.trim() === '') {
      throw badRequest('ownerId must not be blank')
    }
    if (request.files.length !== EXPECTED_FILE_COUNT) {
      throw badRequest(`exactly ${EXPECTED_FILE_COUNT} files must be declared`)
    }
    for (const file of request.files) {
      if (!file.declaredName || file.declaredName.trim() === '') {
        throw badRequest('declaredName must not be blank')
      }
      if (!(file.declaredSize > 0)) {
        throw badRequest('declaredSize must be positive')
      }
    }

    const submissionId = genId()
    const expiresAt = new Date(Date.now() + SUBMISSION_TTL_MS).toISOString()
    const files: MockFile[] = request.files.map((declared, ordinal) => ({
      fileId: genId(),
      ordinal,
      declaredName: declared.declaredName,
      declaredSize: declared.declaredSize,
      status: 'PENDING',
      sizeBytes: null,
      crc32c: null,
      completeAttempts: 0,
    }))

    saveSubmission({ submissionId, ownerId: request.ownerId, status: 'PENDING', expiresAt, committedAt: null, files })

    return {
      submissionId,
      expiresAt,
      files: files.map((f) => ({ fileId: f.fileId, ordinal: f.ordinal, declaredName: f.declaredName })),
    }
  }

  async issueUploadCredentials(submissionId: string, fileId: string): Promise<UploadCredentialsResponse> {
    const submission = requireSubmission(submissionId)
    if (submission.status !== 'PENDING') {
      throw conflict(`submission is not PENDING: ${submissionId}`)
    }
    const file = requireFile(submission, fileId)
    if (file.status === 'UPLOADED') {
      throw conflict(`file already uploaded: ${fileId}`)
    }

    file.status = 'UPLOADING'

    return {
      uploadUrl: `mock://upload/${fileId}`,
      headers: {
        'x-goog-content-length-range': `${file.declaredSize}-${file.declaredSize}`,
        'x-goog-if-generation-match': '0',
      },
      expiresAt: new Date(Date.now() + CREDENTIAL_TTL_MS).toISOString(),
      contentLengthMin: file.declaredSize,
      contentLengthMax: file.declaredSize,
    }
  }

  async completeFile(submissionId: string, fileId: string): Promise<CompleteFileResponse> {
    const submission = requireSubmission(submissionId)
    if (submission.status !== 'PENDING') {
      throw conflict(`submission is not PENDING: ${submissionId}`)
    }
    const file = requireFile(submission, fileId)
    if (file.status === 'UPLOADED') {
      return toCompleteResponse(file)
    }
    if (file.status === 'FAILED') {
      throw conflict(`file previously failed, re-issue upload credentials first: ${fileId}`)
    }

    file.completeAttempts += 1
    if (shouldFail(file.declaredName, file.completeAttempts, this.randomFailuresEnabled())) {
      file.status = 'FAILED'
      throw unprocessable(`uploaded object not found for file: ${fileId}`)
    }

    file.status = 'UPLOADED'
    file.sizeBytes = file.declaredSize
    file.crc32c = crc32cLike(fileId)
    return toCompleteResponse(file)
  }

  async getSubmission(submissionId: string): Promise<SubmissionStatusResponse> {
    return toStatusResponse(requireSubmission(submissionId))
  }

  async commit(submissionId: string): Promise<CommitResponse> {
    const submission = requireSubmission(submissionId)
    if (submission.status === 'COMMITTED') {
      // Idempotent short-circuit — same as the real backend's already-COMMITTED response.
      return { submissionId: submission.submissionId, status: submission.status, committedAt: submission.committedAt! }
    }
    if (submission.status !== 'PENDING') {
      throw conflict(`submission cannot be committed from status ${submission.status}: ${submissionId}`)
    }
    const allUploaded =
      submission.files.length === EXPECTED_FILE_COUNT &&
      submission.files.every((f) => f.status === 'UPLOADED' && f.crc32c !== null && f.sizeBytes === f.declaredSize)
    if (!allUploaded) {
      throw conflict(`not all files are uploaded and verified for submission: ${submissionId}`)
    }

    submission.status = 'COMMITTED'
    submission.committedAt = new Date().toISOString()

    return { submissionId: submission.submissionId, status: submission.status, committedAt: submission.committedAt }
  }
}

// Simulates upload latency/progress for the mock:// URLs issueUploadCredentials hands out —
// there is no real network PUT target in mock mode. Detected by getBackend() via URL scheme,
// so this branch is invisible to UI components.
export function mockPutFile(
  uploadUrl: string,
  _headers: Record<string, string>,
  file: File,
  onProgress: (percent: number) => void,
): Promise<void> {
  return new Promise((resolve) => {
    if (!uploadUrl.startsWith('mock://')) {
      resolve()
      return
    }
    const durationMs = 400 + Math.min(1600, file.size / 50_000) + Math.random() * 400
    const tickMs = 80
    let elapsed = 0
    const timer = setInterval(() => {
      elapsed += tickMs
      const percent = Math.min(100, Math.round((elapsed / durationMs) * 100))
      onProgress(percent)
      if (percent >= 100) {
        clearInterval(timer)
        resolve()
      }
    }, tickMs)
  })
}

function requireSubmission(submissionId: string): MockSubmission {
  const submission = getSubmission(submissionId)
  if (!submission) {
    throw notFound(`submission not found: ${submissionId}`)
  }
  return submission
}

function requireFile(submission: MockSubmission, fileId: string): MockFile {
  const file = submission.files.find((f) => f.fileId === fileId)
  if (!file) {
    throw notFound(`file not found: ${fileId}`)
  }
  return file
}

function toCompleteResponse(file: MockFile): CompleteFileResponse {
  return { fileId: file.fileId, status: file.status, sizeBytes: file.sizeBytes, crc32c: file.crc32c }
}

function toStatusResponse(submission: MockSubmission): SubmissionStatusResponse {
  const files: FileStatusView[] = submission.files.map((f) => ({
    fileId: f.fileId,
    ordinal: f.ordinal,
    declaredName: f.declaredName,
    status: f.status,
    sizeBytes: f.sizeBytes,
    crc32c: f.crc32c,
  }))
  return {
    submissionId: submission.submissionId,
    status: submission.status,
    expiresAt: submission.expiresAt,
    committedAt: submission.committedAt,
    files,
  }
}

// Cosmetic only — a stable pseudo-base64 value, not a real CRC32C checksum.
function crc32cLike(seed: string): string {
  let hash = 0
  for (let i = 0; i < seed.length; i++) {
    hash = (hash * 31 + seed.charCodeAt(i)) >>> 0
  }
  return btoa(String.fromCharCode(hash & 0xff, (hash >> 8) & 0xff, (hash >> 16) & 0xff, (hash >> 24) & 0xff))
}

function badRequest(detail: string): ApiError {
  return new ApiError({ title: 'Bad Request', status: 400, detail })
}
function notFound(detail: string): ApiError {
  return new ApiError({ title: 'Not Found', status: 404, detail })
}
function conflict(detail: string): ApiError {
  return new ApiError({ title: 'Conflict', status: 409, detail })
}
function unprocessable(detail: string): ApiError {
  return new ApiError({ title: 'Unprocessable Entity', status: 422, detail })
}

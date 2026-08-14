import type {
  CommitResponse,
  CompleteFileResponse,
  CreateSubmissionRequest,
  CreateSubmissionResponse,
  SubmissionStatusResponse,
  UploadCredentialsResponse,
} from '../types/api'

// Shape of the RFC7807 application/problem+json body GlobalExceptionHandler returns.
export interface ApiProblem {
  type?: string
  title: string
  status: number
  detail: string
  instance?: string
}

export class ApiError extends Error {
  readonly problem: ApiProblem

  constructor(problem: ApiProblem) {
    super(problem.detail)
    this.name = 'ApiError'
    this.problem = problem
  }
}

// One interface, two implementations (RealApiClient / MockApiClient) — UI code depends only
// on this, never on which backend is actually running.
export interface FileIntakeApiClient {
  createSubmission(request: CreateSubmissionRequest): Promise<CreateSubmissionResponse>
  issueUploadCredentials(submissionId: string, fileId: string): Promise<UploadCredentialsResponse>
  completeFile(submissionId: string, fileId: string): Promise<CompleteFileResponse>
  getSubmission(submissionId: string): Promise<SubmissionStatusResponse>
  commit(submissionId: string): Promise<CommitResponse>
}

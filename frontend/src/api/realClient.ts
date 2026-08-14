import { ApiError, type ApiProblem, type FileIntakeApiClient } from './client'
import type {
  CommitResponse,
  CompleteFileResponse,
  CreateSubmissionRequest,
  CreateSubmissionResponse,
  SubmissionStatusResponse,
  UploadCredentialsResponse,
} from '../types/api'

export class RealApiClient implements FileIntakeApiClient {
  private readonly baseUrl: string

  constructor(baseUrl: string) {
    this.baseUrl = baseUrl
  }

  createSubmission(request: CreateSubmissionRequest): Promise<CreateSubmissionResponse> {
    return this.request('POST', '/submissions', request)
  }

  issueUploadCredentials(submissionId: string, fileId: string): Promise<UploadCredentialsResponse> {
    return this.request('POST', `/submissions/${submissionId}/files/${fileId}/upload-credentials`)
  }

  completeFile(submissionId: string, fileId: string): Promise<CompleteFileResponse> {
    return this.request('POST', `/submissions/${submissionId}/files/${fileId}/complete`)
  }

  getSubmission(submissionId: string): Promise<SubmissionStatusResponse> {
    return this.request('GET', `/submissions/${submissionId}`)
  }

  commit(submissionId: string): Promise<CommitResponse> {
    return this.request('POST', `/submissions/${submissionId}/commit`)
  }

  private async request<T>(method: 'GET' | 'POST', path: string, body?: unknown): Promise<T> {
    const response = await fetch(`${this.baseUrl}${path}`, {
      method,
      headers: body === undefined ? undefined : { 'Content-Type': 'application/json' },
      body: body === undefined ? undefined : JSON.stringify(body),
    })

    if (!response.ok) {
      throw new ApiError(await parseProblem(response))
    }
    return (await response.json()) as T
  }
}

async function parseProblem(response: Response): Promise<ApiProblem> {
  try {
    const problem = (await response.json()) as Partial<ApiProblem>
    return {
      type: problem.type,
      title: problem.title ?? response.statusText,
      status: problem.status ?? response.status,
      detail: problem.detail ?? `request failed with HTTP ${response.status}`,
      instance: problem.instance,
    }
  } catch {
    return { title: response.statusText, status: response.status, detail: `request failed with HTTP ${response.status}` }
  }
}

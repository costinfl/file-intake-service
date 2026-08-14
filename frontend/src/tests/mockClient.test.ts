import { beforeEach, describe, expect, it } from 'vitest'
import { ApiError } from '../api/client'
import { MockApiClient } from '../mock/mockClient'
import { resetStore } from '../mock/store'
import type { CreateSubmissionRequest } from '../types/api'

function tenFileRequest(ownerId = 'owner-1'): CreateSubmissionRequest {
  return {
    ownerId,
    files: Array.from({ length: 10 }, (_, i) => ({ declaredName: `file-${i}.bin`, declaredSize: 1024 })),
  }
}

async function expectApiError(promise: Promise<unknown>, status: number): Promise<void> {
  await expect(promise).rejects.toBeInstanceOf(ApiError)
  await promise.catch((err: unknown) => {
    expect((err as ApiError).problem.status).toBe(status)
  })
}

describe('MockApiClient', () => {
  let client: MockApiClient

  beforeEach(() => {
    resetStore()
    client = new MockApiClient({ randomFailuresEnabled: () => false })
  })

  it('creates a submission with 10 pending files', async () => {
    const response = await client.createSubmission(tenFileRequest())

    expect(response.files).toHaveLength(10)
    expect(response.files[0].ordinal).toBe(0)
    expect(response.files[9].ordinal).toBe(9)
  })

  it('rejects a submission that does not declare exactly 10 files', async () => {
    const request = tenFileRequest()
    request.files = request.files.slice(0, 9)

    await expectApiError(client.createSubmission(request), 400)
  })

  it('returns 404 for an unknown submission', async () => {
    await expectApiError(client.issueUploadCredentials('does-not-exist', 'also-missing'), 404)
  })

  it('returns 404 for an unknown file under a known submission', async () => {
    const submission = await client.createSubmission(tenFileRequest())

    await expectApiError(client.issueUploadCredentials(submission.submissionId, 'does-not-exist'), 404)
  })

  it('returns 409 when issuing credentials for an already-uploaded file', async () => {
    const submission = await client.createSubmission(tenFileRequest())
    const fileId = submission.files[0].fileId
    await client.issueUploadCredentials(submission.submissionId, fileId)
    await client.completeFile(submission.submissionId, fileId)

    await expectApiError(client.issueUploadCredentials(submission.submissionId, fileId), 409)
  })

  it('returns 422 and marks the file FAILED when the declared name triggers the failure scenario', async () => {
    const request = tenFileRequest()
    request.files[0] = { declaredName: 'please-fail.bin', declaredSize: 1024 }
    const submission = await client.createSubmission(request)
    const fileId = submission.files[0].fileId
    await client.issueUploadCredentials(submission.submissionId, fileId)

    await expectApiError(client.completeFile(submission.submissionId, fileId), 422)

    const status = await client.getSubmission(submission.submissionId)
    expect(status.files[0].status).toBe('FAILED')
  })

  it('returns 409 when completing a file that previously failed, without re-issuing credentials', async () => {
    const request = tenFileRequest()
    request.files[0] = { declaredName: 'please-fail.bin', declaredSize: 1024 }
    const submission = await client.createSubmission(request)
    const fileId = submission.files[0].fileId
    await client.issueUploadCredentials(submission.submissionId, fileId)
    await client.completeFile(submission.submissionId, fileId).catch(() => undefined)

    await expectApiError(client.completeFile(submission.submissionId, fileId), 409)
  })

  it('succeeds on retry after re-issuing credentials for a failed file', async () => {
    const request = tenFileRequest()
    request.files[0] = { declaredName: 'please-fail.bin', declaredSize: 1024 }
    const submission = await client.createSubmission(request)
    const fileId = submission.files[0].fileId
    await client.issueUploadCredentials(submission.submissionId, fileId)
    await client.completeFile(submission.submissionId, fileId).catch(() => undefined)

    await client.issueUploadCredentials(submission.submissionId, fileId)
    const result = await client.completeFile(submission.submissionId, fileId)

    expect(result.status).toBe('UPLOADED')
  })

  it('returns 409 on commit when not all files are uploaded', async () => {
    const submission = await client.createSubmission(tenFileRequest())

    await expectApiError(client.commit(submission.submissionId), 409)
  })

  it('commits once all 10 files are uploaded, and commit is idempotent', async () => {
    const submission = await client.createSubmission(tenFileRequest())
    for (const file of submission.files) {
      await client.issueUploadCredentials(submission.submissionId, file.fileId)
      await client.completeFile(submission.submissionId, file.fileId)
    }

    const first = await client.commit(submission.submissionId)
    const second = await client.commit(submission.submissionId)

    expect(first.status).toBe('COMMITTED')
    expect(second.committedAt).toBe(first.committedAt)
  })
})

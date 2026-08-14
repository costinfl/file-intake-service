import { useCallback, useReducer } from 'react'
import { getBackend } from '../api/backend'
import { ApiError } from '../api/client'
import type { FileStatus, SubmissionStatus } from '../types/api'

export interface FileSlot {
  fileId: string
  ordinal: number
  declaredName: string
  declaredSize: number | null
  status: FileStatus
  progress: number
  sizeBytes: number | null
  crc32c: string | null
  errorDetail: string | null
  localFile: File | null
}

export interface FlowState {
  phase: 'idle' | 'creating' | 'active' | 'committing' | 'committed'
  submissionId: string | null
  expiresAt: string | null
  submissionStatus: SubmissionStatus | null
  committedAt: string | null
  files: FileSlot[]
  error: string | null
}

const initialState: FlowState = {
  phase: 'idle',
  submissionId: null,
  expiresAt: null,
  submissionStatus: null,
  committedAt: null,
  files: [],
  error: null,
}

type Action =
  | { type: 'CREATE_START' }
  | { type: 'CREATE_ERROR'; error: string }
  | { type: 'CREATE_SUCCESS'; submissionId: string; expiresAt: string; files: FileSlot[] }
  | {
      type: 'FILE_STATUS'
      fileId: string
      status: FileStatus
      progress?: number
      sizeBytes?: number | null
      crc32c?: string | null
      errorDetail?: string | null
    }
  | { type: 'COMMIT_START' }
  | { type: 'COMMIT_SUCCESS'; status: SubmissionStatus; committedAt: string }
  | { type: 'COMMIT_ERROR'; error: string }
  | { type: 'LOADED'; submissionId: string; expiresAt: string; status: SubmissionStatus; committedAt: string | null; files: FileSlot[] }
  | { type: 'RESET' }

function reducer(state: FlowState, action: Action): FlowState {
  switch (action.type) {
    case 'CREATE_START':
      return { ...initialState, phase: 'creating' }
    case 'CREATE_ERROR':
      return { ...state, phase: state.submissionId ? state.phase : 'idle', error: action.error }
    case 'CREATE_SUCCESS':
      return {
        ...state,
        phase: 'active',
        submissionId: action.submissionId,
        expiresAt: action.expiresAt,
        submissionStatus: 'PENDING',
        files: action.files,
        error: null,
      }
    case 'FILE_STATUS':
      return {
        ...state,
        files: state.files.map((f) =>
          f.fileId === action.fileId
            ? {
                ...f,
                status: action.status,
                progress: action.progress ?? f.progress,
                sizeBytes: action.sizeBytes ?? f.sizeBytes,
                crc32c: action.crc32c ?? f.crc32c,
                errorDetail: action.errorDetail ?? null,
              }
            : f,
        ),
      }
    case 'COMMIT_START':
      return { ...state, phase: 'committing', error: null }
    case 'COMMIT_SUCCESS':
      return { ...state, phase: 'committed', submissionStatus: action.status, committedAt: action.committedAt }
    case 'COMMIT_ERROR':
      return { ...state, phase: 'active', error: action.error }
    case 'LOADED':
      return {
        ...state,
        phase: action.status === 'COMMITTED' ? 'committed' : 'active',
        submissionId: action.submissionId,
        expiresAt: action.expiresAt,
        submissionStatus: action.status,
        committedAt: action.committedAt,
        files: action.files,
        error: null,
      }
    case 'RESET':
      return initialState
    default:
      return state
  }
}

const UPLOAD_CONCURRENCY = 3

async function runWithLimit<T>(items: T[], limit: number, worker: (item: T) => Promise<void>): Promise<void> {
  let index = 0
  async function next(): Promise<void> {
    const current = index++
    if (current >= items.length) {
      return
    }
    await worker(items[current])
    await next()
  }
  await Promise.all(Array.from({ length: Math.min(limit, items.length) }, () => next()))
}

function describeError(err: unknown): string {
  if (err instanceof ApiError) {
    return err.problem.detail
  }
  if (err instanceof Error) {
    return err.message
  }
  return 'unknown error'
}

export function useSubmissionFlow() {
  const [state, dispatch] = useReducer(reducer, initialState)

  const uploadOne = useCallback(async (submissionId: string, slot: FileSlot) => {
    if (!slot.localFile) {
      return
    }
    const backend = getBackend()
    try {
      dispatch({ type: 'FILE_STATUS', fileId: slot.fileId, status: 'UPLOADING', progress: 0 })
      const credentials = await backend.api.issueUploadCredentials(submissionId, slot.fileId)
      await backend.putFile(credentials.uploadUrl, credentials.headers, slot.localFile, (progress) =>
        dispatch({ type: 'FILE_STATUS', fileId: slot.fileId, status: 'UPLOADING', progress }),
      )
      const result = await backend.api.completeFile(submissionId, slot.fileId)
      dispatch({
        type: 'FILE_STATUS',
        fileId: slot.fileId,
        status: result.status,
        progress: 100,
        sizeBytes: result.sizeBytes,
        crc32c: result.crc32c,
      })
    } catch (err) {
      dispatch({ type: 'FILE_STATUS', fileId: slot.fileId, status: 'FAILED', errorDetail: describeError(err) })
    }
  }, [])

  const create = useCallback(
    async (ownerId: string, localFiles: File[]) => {
      if (localFiles.length !== 10) {
        dispatch({ type: 'CREATE_ERROR', error: 'exactly 10 files must be selected' })
        return
      }
      dispatch({ type: 'CREATE_START' })
      try {
        const backend = getBackend()
        const response = await backend.api.createSubmission({
          ownerId,
          files: localFiles.map((f) => ({ declaredName: f.name, declaredSize: f.size })),
        })
        const slots: FileSlot[] = response.files
          .slice()
          .sort((a, b) => a.ordinal - b.ordinal)
          .map((ref) => ({
            fileId: ref.fileId,
            ordinal: ref.ordinal,
            declaredName: ref.declaredName,
            declaredSize: localFiles[ref.ordinal].size,
            status: 'PENDING',
            progress: 0,
            sizeBytes: null,
            crc32c: null,
            errorDetail: null,
            localFile: localFiles[ref.ordinal],
          }))
        dispatch({ type: 'CREATE_SUCCESS', submissionId: response.submissionId, expiresAt: response.expiresAt, files: slots })
        await runWithLimit(slots, UPLOAD_CONCURRENCY, (slot) => uploadOne(response.submissionId, slot))
      } catch (err) {
        dispatch({ type: 'CREATE_ERROR', error: describeError(err) })
      }
    },
    [uploadOne],
  )

  const retryFile = useCallback(
    async (fileId: string) => {
      if (!state.submissionId) {
        return
      }
      const slot = state.files.find((f) => f.fileId === fileId)
      if (!slot) {
        return
      }
      await uploadOne(state.submissionId, slot)
    },
    [state.submissionId, state.files, uploadOne],
  )

  const commit = useCallback(async () => {
    if (!state.submissionId) {
      return
    }
    dispatch({ type: 'COMMIT_START' })
    try {
      const backend = getBackend()
      const result = await backend.api.commit(state.submissionId)
      dispatch({ type: 'COMMIT_SUCCESS', status: result.status, committedAt: result.committedAt })
    } catch (err) {
      dispatch({ type: 'COMMIT_ERROR', error: describeError(err) })
    }
  }, [state.submissionId])

  const loadExisting = useCallback(async (submissionId: string) => {
    dispatch({ type: 'CREATE_START' })
    try {
      const backend = getBackend()
      const response = await backend.api.getSubmission(submissionId)
      const slots: FileSlot[] = response.files.map((f) => ({
        fileId: f.fileId,
        ordinal: f.ordinal,
        declaredName: f.declaredName,
        declaredSize: null,
        status: f.status,
        progress: f.status === 'UPLOADED' ? 100 : 0,
        sizeBytes: f.sizeBytes,
        crc32c: f.crc32c,
        errorDetail: null,
        localFile: null,
      }))
      dispatch({
        type: 'LOADED',
        submissionId: response.submissionId,
        expiresAt: response.expiresAt,
        status: response.status,
        committedAt: response.committedAt,
        files: slots,
      })
    } catch (err) {
      dispatch({ type: 'CREATE_ERROR', error: describeError(err) })
    }
  }, [])

  const reset = useCallback(() => dispatch({ type: 'RESET' }), [])

  return { state, create, retryFile, commit, loadExisting, reset }
}

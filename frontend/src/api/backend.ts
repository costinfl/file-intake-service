import type { FileIntakeApiClient } from './client'
import { RealApiClient } from './realClient'
import { realPutFile } from './upload'
import { MockApiClient, mockPutFile } from '../mock/mockClient'
import { loadSettings } from '../state/settings'

export interface Backend {
  api: FileIntakeApiClient
  putFile: (uploadUrl: string, headers: Record<string, string>, file: File, onProgress: (percent: number) => void) => Promise<void>
}

// One singleton so its in-memory store persists across calls within the page session; it reads
// the random-failures toggle fresh on every completeFile call rather than capturing it once.
const mockApiClient = new MockApiClient({ randomFailuresEnabled: () => loadSettings().randomFailuresEnabled })
const mockBackend: Backend = { api: mockApiClient, putFile: mockPutFile }

// UI components call getBackend().api.createSubmission(...) etc. and never see which mode is
// active — that decision lives entirely here.
export function getBackend(): Backend {
  const settings = loadSettings()
  if (settings.mode === 'real' && settings.apiBaseUrl.trim() !== '') {
    return { api: new RealApiClient(settings.apiBaseUrl), putFile: realPutFile }
  }
  return mockBackend
}

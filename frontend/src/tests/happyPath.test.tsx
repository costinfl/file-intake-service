import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { cleanup, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import App from '../App'
import { resetStore } from '../mock/store'

function makeFile(name: string): File {
  return new File(['x'], name, { type: 'application/octet-stream' })
}

beforeEach(() => {
  localStorage.clear()
  resetStore()
})

afterEach(() => {
  cleanup()
})

describe('happy path (mock mode)', () => {
  it(
    'creates a submission, uploads all 10 files, and commits',
    async () => {
      const user = userEvent.setup()
      const { container } = render(<App />)

      const files = Array.from({ length: 10 }, (_, i) => makeFile(`file-${i}.bin`))
      const input = container.querySelector('input[type="file"]') as HTMLInputElement
      await user.upload(input, files)

      expect(await screen.findByText('10/10 files selected')).toBeInTheDocument()

      await user.click(screen.getByRole('button', { name: /create submission/i }))

      await waitFor(
        () => {
          expect(screen.getAllByText('Uploaded')).toHaveLength(10)
        },
        { timeout: 15000 },
      )

      const commitButton = screen.getByRole('button', { name: /commit submission/i })
      expect(commitButton).toBeEnabled()
      await user.click(commitButton)

      expect(await screen.findByText(/^Committed at/)).toBeInTheDocument()
    },
    20000,
  )
})

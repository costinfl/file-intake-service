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

describe('retry path (mock mode)', () => {
  it(
    'shows a failed file with a Retry button, and retrying drives it to Uploaded, then commits',
    async () => {
      const user = userEvent.setup()
      const { container } = render(<App />)

      const files = [
        makeFile('fail-me.pdf'),
        ...Array.from({ length: 9 }, (_, i) => makeFile(`file-${i}.bin`)),
      ]
      const input = container.querySelector('input[type="file"]') as HTMLInputElement
      await user.upload(input, files)
      await user.click(screen.getByRole('button', { name: /create submission/i }))

      const retryButton = await waitFor(
        () => {
          const button = screen.getByRole('button', { name: /retry/i })
          expect(button).toBeEnabled()
          return button
        },
        { timeout: 15000 },
      )

      await user.click(retryButton)

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
    25000,
  )
})

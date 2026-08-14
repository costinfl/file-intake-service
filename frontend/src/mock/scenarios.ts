// Deterministic: any file named containing "fail" fails exactly once, then succeeds on retry —
// lets a demo user or a test trigger the FAILED/retry path on demand. The random toggle adds
// organic failures for a live, unscripted demo.
export function shouldFail(declaredName: string, attemptNumber: number, randomFailuresEnabled: boolean): boolean {
  if (declaredName.toLowerCase().includes('fail') && attemptNumber === 1) {
    return true
  }
  if (randomFailuresEnabled) {
    return Math.random() < 0.12
  }
  return false
}

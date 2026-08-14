// Uses XMLHttpRequest rather than fetch specifically because fetch has no upload-progress
// event and this UI's whole point is per-file progress bars; xhr.upload.onprogress gives us
// that while still just setting the backend-provided headers and PUTting the file as the body
// — no client-side signing needed since the backend's signed URL is already fully authorized.
export function realPutFile(
  uploadUrl: string,
  headers: Record<string, string>,
  file: File,
  onProgress: (percent: number) => void,
): Promise<void> {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest()
    xhr.open('PUT', uploadUrl, true)
    for (const [name, value] of Object.entries(headers)) {
      xhr.setRequestHeader(name, value)
    }
    xhr.upload.onprogress = (event) => {
      if (event.lengthComputable) {
        onProgress(Math.round((event.loaded / event.total) * 100))
      }
    }
    xhr.onload = () => {
      if (xhr.status >= 200 && xhr.status < 300) {
        resolve()
      } else {
        reject(new Error(`upload failed: HTTP ${xhr.status}`))
      }
    }
    xhr.onerror = () => reject(new Error('upload network error'))
    xhr.send(file)
  })
}

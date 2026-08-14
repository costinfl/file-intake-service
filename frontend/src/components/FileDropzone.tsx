import { useCallback, useRef, useState, type ChangeEvent, type DragEvent, type KeyboardEvent } from 'react'

interface FileDropzoneProps {
  onFilesSelected: (files: File[]) => void
}

export function FileDropzone({ onFilesSelected }: FileDropzoneProps) {
  const inputRef = useRef<HTMLInputElement>(null)
  const [isDragOver, setIsDragOver] = useState(false)

  const handleFiles = useCallback(
    (fileList: FileList | null) => {
      if (!fileList) {
        return
      }
      onFilesSelected(Array.from(fileList))
    },
    [onFilesSelected],
  )

  const openPicker = useCallback(() => inputRef.current?.click(), [])

  return (
    <div
      className={`dropzone${isDragOver ? ' dropzone--active' : ''}`}
      onClick={openPicker}
      onKeyDown={(event: KeyboardEvent<HTMLDivElement>) => {
        if (event.key === 'Enter' || event.key === ' ') {
          openPicker()
        }
      }}
      onDragOver={(event: DragEvent<HTMLDivElement>) => {
        event.preventDefault()
        setIsDragOver(true)
      }}
      onDragLeave={() => setIsDragOver(false)}
      onDrop={(event: DragEvent<HTMLDivElement>) => {
        event.preventDefault()
        setIsDragOver(false)
        handleFiles(event.dataTransfer.files)
      }}
      role="button"
      tabIndex={0}
    >
      <p>Drag and drop exactly 10 files here, or click to browse</p>
      <input
        ref={inputRef}
        type="file"
        multiple
        className="visually-hidden"
        onChange={(event: ChangeEvent<HTMLInputElement>) => handleFiles(event.target.files)}
        aria-label="Choose files"
      />
    </div>
  )
}

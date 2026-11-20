/** "820 B", "14 KB", "3.2 MB": the size of a file for a list. */
export function formatBytes(bytes: number): string {
  if (bytes < 1024) {
    return `${bytes} B`;
  }
  if (bytes < 1024 * 1024) {
    return `${Math.round(bytes / 1024)} KB`;
  }
  const mb = bytes / (1024 * 1024);
  return `${mb < 10 ? mb.toFixed(1) : Math.round(mb)} MB`;
}

/** The limit the API keeps for one file. The API refuses a larger one with its own message as well. */
export const MAX_FILE_BYTES = 20 * 1024 * 1024;

/**
 * The files that are worth sending, and a line for each one that is not. Only the size
 * is checked here: what a file is, the API decides from its bytes.
 */
export function filesToSend(files: ArrayLike<File>): { send: File[]; refused: string[] } {
  const send: File[] = [];
  const refused: string[] = [];
  for (const file of Array.from(files)) {
    if (file.size > MAX_FILE_BYTES) {
      refused.push(`${file.name} is larger than ${formatBytes(MAX_FILE_BYTES)}, the most a file can be.`);
    } else {
      send.push(file);
    }
  }
  return { send, refused };
}

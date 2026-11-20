/**
 * Where the highlight goes in a list of `count` options when `key` is pressed, or null
 * when the key does not move it. The list wraps around at both ends.
 */
export function moveHighlight(key: string, current: number, count: number): number | null {
  if (count === 0) {
    return null;
  }
  if (key === 'ArrowDown') {
    return (current + 1) % count;
  }
  if (key === 'ArrowUp') {
    return (current + count - 1) % count;
  }
  return null;
}

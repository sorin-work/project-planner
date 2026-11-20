import { MAX_FILE_BYTES, filesToSend, formatBytes } from './attachment-files';

describe('attachment files', () => {
  it('says a size the way a list of files does', () => {
    expect(formatBytes(820)).toBe('820 B');
    expect(formatBytes(14 * 1024 + 300)).toBe('14 KB');
    expect(formatBytes(3.2 * 1024 * 1024)).toBe('3.2 MB');
    expect(formatBytes(20 * 1024 * 1024)).toBe('20 MB');
  });

  it('keeps back a file that is too large and says why', () => {
    const small = new File(['brief'], 'brief.txt');
    const big = { name: 'video.mp4', size: MAX_FILE_BYTES + 1 } as File;
    const { send, refused } = filesToSend([small, big]);
    expect(send).toEqual([small]);
    expect(refused).toEqual(['video.mp4 is larger than 20 MB, the most a file can be.']);
  });
});

export type DiffSegmentType = 'same' | 'add' | 'del'

export interface DiffSegment {
  type: DiffSegmentType
  text: string
}

function toChars(input: string): string[] {
  return Array.from(input)
}

export function diffText(reference: string, hypothesis: string): DiffSegment[] {
  const a = toChars(reference)
  const b = toChars(hypothesis)
  const m = a.length
  const n = b.length
  const dp = Array.from({ length: m + 1 }, () => new Array<number>(n + 1).fill(0))

  for (let i = m - 1; i >= 0; i -= 1) {
    for (let j = n - 1; j >= 0; j -= 1) {
      dp[i][j] = a[i] === b[j] ? dp[i + 1][j + 1] + 1 : Math.max(dp[i + 1][j], dp[i][j + 1])
    }
  }

  const segments: DiffSegment[] = []
  let i = 0
  let j = 0

  const push = (type: DiffSegmentType, text: string) => {
    if (!text) return
    const last = segments[segments.length - 1]
    if (last && last.type === type) {
      last.text += text
      return
    }
    segments.push({ type, text })
  }

  while (i < m && j < n) {
    if (a[i] === b[j]) {
      push('same', a[i])
      i += 1
      j += 1
      continue
    }

    if (dp[i + 1][j] >= dp[i][j + 1]) {
      push('del', a[i])
      i += 1
    } else {
      push('add', b[j])
      j += 1
    }
  }

  while (i < m) {
    push('del', a[i])
    i += 1
  }

  while (j < n) {
    push('add', b[j])
    j += 1
  }

  return segments
}

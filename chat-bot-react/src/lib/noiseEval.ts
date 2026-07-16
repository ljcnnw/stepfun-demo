import type { Vendor, VendorEvalResult } from './asrEval'

export interface NoiseCaseRecord {
  id: string
  name: string
  sourceCaseId?: string
  noiseProfile?: string
  noiseType?: string
  targetSnrDb?: number
  vendors: Record<Vendor, VendorEvalResult>
}

export interface NoiseProfileSummary {
  profile: string
  label: string
  validPairs: number
  passRetention: number | null
  avgCerDelta: number | null
}

export interface NoiseVendorSummary {
  vendor: Vendor
  validPairs: number
  cleanPassedPairs: number
  retainedPassPairs: number
  passRetention: number | null
  avgCerDelta: number | null
  entityRetention: number | null
  robustnessScore: number | null
  profiles: NoiseProfileSummary[]
}

function isDone(result: VendorEvalResult | undefined): result is VendorEvalResult {
  return Boolean(result && result.status === 'done')
}

function average(values: number[]): number | null {
  return values.length ? values.reduce((sum, value) => sum + value, 0) / values.length : null
}

function profileLabel(profile: string, type?: string, targetSnrDb?: number): string {
  const known: Record<string, string> = {
    white_20: '白噪 20 dB',
    pink_10: '粉红噪 10 dB',
    babble_5: '背景人声 5 dB',
  }
  if (known[profile]) return known[profile]
  const typeLabel = type === 'babble' ? '背景人声' : type === 'pink' ? '粉红噪' : type === 'white' ? '白噪' : profile
  return typeof targetSnrDb === 'number' ? `${typeLabel} ${targetSnrDb} dB` : typeLabel
}

export function calculateNoiseVendorSummaries(cases: NoiseCaseRecord[], vendors: Vendor[]): NoiseVendorSummary[] {
  const cleanCases = new Map(cases.map(caseItem => [caseItem.id, caseItem]))
  const noiseCases = cases.filter(caseItem => Boolean(caseItem.sourceCaseId && caseItem.noiseProfile))
  const profileDefinitions = Array.from(new Map(noiseCases.map(caseItem => [caseItem.noiseProfile!, {
    profile: caseItem.noiseProfile!,
    label: profileLabel(caseItem.noiseProfile!, caseItem.noiseType, caseItem.targetSnrDb),
  }])).values())

  return vendors.map(vendor => {
    const validPairs: Array<{ clean: VendorEvalResult; noisy: VendorEvalResult; profile: string }> = []
    noiseCases.forEach(noisyCase => {
      const cleanCase = cleanCases.get(noisyCase.sourceCaseId!)
      const clean = cleanCase?.vendors[vendor]
      const noisy = noisyCase.vendors[vendor]
      if (isDone(clean) && isDone(noisy)) validPairs.push({ clean, noisy, profile: noisyCase.noiseProfile! })
    })

    const cleanPassedPairs = validPairs.filter(pair => pair.clean.pass === true)
    const retainedPassPairs = cleanPassedPairs.filter(pair => pair.noisy.pass === true)
    const cerDeltas = validPairs.flatMap(pair => pair.clean.cer === null || pair.noisy.cer === null ? [] : [pair.noisy.cer - pair.clean.cer])
    const entityBaseline = validPairs.filter(pair => pair.clean.entityAccuracy === 1)
    const entityRetained = entityBaseline.filter(pair => pair.noisy.entityAccuracy === 1)
    const passRetention = cleanPassedPairs.length ? retainedPassPairs.length / cleanPassedPairs.length : null
    const avgCerDelta = average(cerDeltas)
    const cerStability = avgCerDelta === null ? null : Math.max(0, 1 - Math.max(avgCerDelta, 0) / 0.2)
    const robustnessScore = passRetention === null || cerStability === null ? null : 0.7 * passRetention + 0.3 * cerStability

    return {
      vendor,
      validPairs: validPairs.length,
      cleanPassedPairs: cleanPassedPairs.length,
      retainedPassPairs: retainedPassPairs.length,
      passRetention,
      avgCerDelta,
      entityRetention: entityBaseline.length ? entityRetained.length / entityBaseline.length : null,
      robustnessScore,
      profiles: profileDefinitions.map(definition => {
        const profilePairs = validPairs.filter(pair => pair.profile === definition.profile)
        const profileCleanPassed = profilePairs.filter(pair => pair.clean.pass === true)
        const profileRetained = profileCleanPassed.filter(pair => pair.noisy.pass === true)
        const deltas = profilePairs.flatMap(pair => pair.clean.cer === null || pair.noisy.cer === null ? [] : [pair.noisy.cer - pair.clean.cer])
        return {
          ...definition,
          validPairs: profilePairs.length,
          passRetention: profileCleanPassed.length ? profileRetained.length / profileCleanPassed.length : null,
          avgCerDelta: average(deltas),
        }
      }),
    }
  })
}

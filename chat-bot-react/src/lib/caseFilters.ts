export type CaseLengthFilter = 'all' | 'short' | 'medium' | 'long' | 'unknown'
export type NoiseScenarioFilter = 'all' | 'clean' | 'white_20' | 'pink_10' | 'babble_5' | 'restaurant_10' | 'traffic_10' | 'subway_10' | 'office_15' | 'appliance_15' | 'wind_10' | 'farfield_reverb' | 'other'

export interface FilterableCase {
  durationSeconds?: number
  noiseProfile?: string
  noiseType?: string
  targetSnrDb?: number
}

export const CASE_LENGTH_OPTIONS: Array<{ key: CaseLengthFilter; label: string }> = [
  { key: 'all', label: '全部时长' },
  { key: 'short', label: '短句（≤ 4 秒）' },
  { key: 'medium', label: '中句（4–8 秒）' },
  { key: 'long', label: '长句（> 8 秒）' },
  { key: 'unknown', label: '未标注时长' },
]

export const NOISE_SCENARIO_OPTIONS: Array<{ key: NoiseScenarioFilter; label: string }> = [
  { key: 'all', label: '全部噪声场景' },
  { key: 'clean', label: '干净音频' },
  { key: 'white_20', label: '白噪 20 dB' },
  { key: 'pink_10', label: '粉红噪 10 dB' },
  { key: 'babble_5', label: '背景人声 5 dB' },
  { key: 'restaurant_10', label: '餐厅环境 10 dB' },
  { key: 'traffic_10', label: '街道交通 10 dB' },
  { key: 'subway_10', label: '地铁环境 10 dB' },
  { key: 'office_15', label: '办公室环境 15 dB' },
  { key: 'appliance_15', label: '家电低频 15 dB' },
  { key: 'wind_10', label: '合成风噪 10 dB' },
  { key: 'farfield_reverb', label: '远讲混响' },
  { key: 'other', label: '其他噪声' },
]

export function getCaseLengthCategory(caseItem: FilterableCase): Exclude<CaseLengthFilter, 'all'> {
  const duration = caseItem.durationSeconds
  if (typeof duration !== 'number' || !Number.isFinite(duration) || duration <= 0) return 'unknown'
  if (duration <= 4) return 'short'
  if (duration <= 8) return 'medium'
  return 'long'
}

export function getCaseLengthLabel(caseItem: FilterableCase): string {
  const labels: Record<Exclude<CaseLengthFilter, 'all'>, string> = {
    short: '短句（≤ 4 秒）',
    medium: '中句（4–8 秒）',
    long: '长句（> 8 秒）',
    unknown: '未标注时长',
  }
  return labels[getCaseLengthCategory(caseItem)]
}

export function getNoiseScenario(caseItem: FilterableCase): Exclude<NoiseScenarioFilter, 'all'> {
  const profile = caseItem.noiseProfile
  if (profile === 'white_20' || profile === 'pink_10' || profile === 'babble_5'
    || profile === 'restaurant_10' || profile === 'traffic_10' || profile === 'subway_10'
    || profile === 'office_15' || profile === 'appliance_15' || profile === 'wind_10' || profile === 'farfield_reverb') return profile
  if (profile) return 'other'
  return 'clean'
}

export function getNoiseScenarioLabel(caseItem: FilterableCase): string {
  const labels: Record<Exclude<NoiseScenarioFilter, 'all'>, string> = {
    clean: '干净音频',
    white_20: '白噪 20 dB',
    pink_10: '粉红噪 10 dB',
    babble_5: '背景人声 5 dB',
    restaurant_10: '餐厅环境 10 dB',
    traffic_10: '街道交通 10 dB',
    subway_10: '地铁环境 10 dB',
    office_15: '办公室环境 15 dB',
    appliance_15: '家电低频 15 dB',
    wind_10: '合成风噪 10 dB',
    farfield_reverb: '远讲混响',
    other: '其他噪声',
  }
  return labels[getNoiseScenario(caseItem)]
}

export function matchesCaseFilters(caseItem: FilterableCase, lengthFilter: CaseLengthFilter, noiseFilter: NoiseScenarioFilter): boolean {
  return (lengthFilter === 'all' || getCaseLengthCategory(caseItem) === lengthFilter)
    && (noiseFilter === 'all' || getNoiseScenario(caseItem) === noiseFilter)
}

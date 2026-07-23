import { useRef, useImperativeHandle, forwardRef } from 'react'
import type { ConfigurableAsrProvider } from '../hooks/useAsrWebSocket'
import './SettingsPanel.css'

interface Props {
  open: boolean
  onClose: () => void
  threshold: number
  onThresholdChange: (val: number) => void
  vadDurations: Record<ConfigurableAsrProvider, number>
  onVadDurationChange: (provider: ConfigurableAsrProvider, value: number) => void
  provider: 'sierra' | 'stepfun'
  onProviderChange: (val: 'sierra' | 'stepfun') => void
  asrProvider: 'stepfun' | 'fano' | 'aliyun' | 'volc'
  onAsrProviderChange: (val: 'stepfun' | 'fano' | 'aliyun' | 'volc') => void
}

export interface SettingsPanelHandle {
  setVolume: (vol: number) => void
}

export const SettingsPanel = forwardRef<SettingsPanelHandle, Props>(({
  open, onClose, threshold, onThresholdChange, provider, onProviderChange,
  asrProvider, onAsrProviderChange, vadDurations, onVadDurationChange
}, ref) => {
  const volumeBarRef = useRef<HTMLDivElement | null>(null)

  useImperativeHandle(ref, () => ({
    setVolume(vol: number) {
      if (volumeBarRef.current) {
        volumeBarRef.current.style.width = `${vol * 100}%`
      }
    }
  }))

  if (!open) return null

  return (
    <div className="settings-overlay" onClick={onClose}>
      <div className="settings-panel" onClick={e => e.stopPropagation()}>
        <div className="settings-header">
          <span>设置</span>
          <button className="settings-close" onClick={onClose}>✕</button>
        </div>

        <div className="settings-body">
          <label className="settings-label">AI 模型</label>
          <div className="provider-row">
            <button
              className={`provider-btn ${provider === 'sierra' ? 'active' : ''}`}
              onClick={() => onProviderChange('sierra')}
            >Sierra</button>
            <button
              className={`provider-btn ${provider === 'stepfun' ? 'active' : ''}`}
              onClick={() => onProviderChange('stepfun')}
            >Stepfun</button>
          </div>

          <label className="settings-label" style={{ marginTop: 16 }}>语音识别（ASR）</label>
          <div className="provider-row">
            <button
              className={`provider-btn ${asrProvider === 'stepfun' ? 'active' : ''}`}
              onClick={() => onAsrProviderChange('stepfun')}
            >Stepfun</button>
            <button
              className={`provider-btn ${asrProvider === 'fano' ? 'active' : ''}`}
              onClick={() => onAsrProviderChange('fano')}
            >FANO</button>
            <button
              className={`provider-btn ${asrProvider === 'aliyun' ? 'active' : ''}`}
              onClick={() => onAsrProviderChange('aliyun')}
            >Paraformer</button>
            <button
              className={`provider-btn ${asrProvider === 'volc' ? 'active' : ''}`}
              onClick={() => onAsrProviderChange('volc')}
            >豆包ASR</button>
          </div>

          <label className="settings-label" style={{ marginTop: 16 }}>麦克风音量阈值</label>
          <p className="settings-desc">
            低于此阈值的音频帧会替换为静音发送，可过滤环境噪音同时保持 VAD 正常工作。
          </p>

          <div className="threshold-bar-wrap">
            <div
              className="threshold-bar-volume"
              ref={volumeBarRef}
              style={{ width: '0%' }}
            />
            <div
              className="threshold-bar-line"
              style={{ left: `${threshold * 100}%` }}
            />
          </div>

          <div className="threshold-row">
            <input
              type="range"
              min={0}
              max={0.5}
              step={0.01}
              value={threshold}
              onChange={e => onThresholdChange(parseFloat(e.target.value))}
              className="threshold-slider"
            />
            <span className="threshold-value">{threshold.toFixed(2)}</span>
            <button
              className="threshold-reset"
              onClick={() => onThresholdChange(0)}
              title="重置为 0"
            >重置</button>
          </div>

          <p className="settings-hint">
            建议值：0.02～0.08。将滑块拖到当前音量指示条的边缘即可。
          </p>

          <label className="settings-label" style={{ marginTop: 20 }}>ASR 判停时长</label>
          <p className="settings-desc">
            连续静音达到该时长后结束当前句。修改后立即生效；阿里云和豆包会自动重连 ASR，以应用新参数。
          </p>
          {([
            ['stepfun', 'Stepfun'],
            ['aliyun', 'Paraformer'],
            ['volc', '豆包ASR'],
          ] as Array<[ConfigurableAsrProvider, string]>).map(([providerKey, label]) => (
            <div className="threshold-row" key={providerKey}>
              <span className="vad-provider-label">{label}</span>
              <input
                type="number"
                min={200}
                max={5000}
                step={100}
                value={vadDurations[providerKey]}
                onChange={event => onVadDurationChange(providerKey, Number(event.target.value))}
                className="vad-duration-input"
              />
              <span className="vad-duration-unit">ms</span>
            </div>
          ))}
        </div>
      </div>
    </div>
  )
})

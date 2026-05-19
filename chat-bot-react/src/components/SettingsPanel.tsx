import React from 'react'
import './SettingsPanel.css'

interface Props {
  open: boolean
  onClose: () => void
  threshold: number
  onThresholdChange: (val: number) => void
  volume: number  // 当前实时音量（0~1），用于直观展示阈值位置
}

export const SettingsPanel: React.FC<Props> = ({
  open, onClose, threshold, onThresholdChange, volume
}) => {
  if (!open) return null

  return (
    <div className="settings-overlay" onClick={onClose}>
      <div className="settings-panel" onClick={e => e.stopPropagation()}>
        <div className="settings-header">
          <span>设置</span>
          <button className="settings-close" onClick={onClose}>✕</button>
        </div>

        <div className="settings-body">
          <label className="settings-label">麦克风音量阈值</label>
          <p className="settings-desc">
            低于此阈值的音频帧会替换为静音发送，可过滤环境噪音同时保持 VAD 正常工作。
          </p>

          {/* 实时音量 + 阈值指示条 */}
          <div className="threshold-bar-wrap">
            {/* 当前音量填充 */}
            <div
              className="threshold-bar-volume"
              style={{ width: `${volume * 100}%` }}
            />
            {/* 阈值指示线 */}
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
        </div>
      </div>
    </div>
  )
}

import React from 'react'
import './WaveAnimation.css'

interface Props {
  active: boolean
  volume?: number  // 0~1，有值时用实时音量驱动，否则用 CSS 动画
}

const BAR_COUNT = 5
// 每根柱子对音量的响应灵敏度，中间柱子最高
const SENSITIVITY = [0.6, 0.8, 1.0, 0.8, 0.6]

export const WaveAnimation: React.FC<Props> = ({ active, volume }) => {
  const hasVolume = active && volume !== undefined

  return (
    <div className={`wave-container ${active ? 'wave-active' : 'wave-idle'}`}>
      {Array.from({ length: BAR_COUNT }).map((_, i) => {
        const style: React.CSSProperties = hasVolume
          ? {
              height: `${8 + volume * SENSITIVITY[i] * 32}px`,
              transition: 'height 80ms ease-out',
              animationName: 'none',
            }
          : { animationDelay: `${i * 0.1}s` }
        return <div key={i} className="wave-bar" style={style} />
      })}
    </div>
  )
}

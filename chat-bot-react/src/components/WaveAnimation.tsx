import React, { useRef, useImperativeHandle, forwardRef } from 'react'
import './WaveAnimation.css'

interface Props {
  active: boolean
}

export interface WaveAnimationHandle {
  setVolume: (vol: number) => void
}

const BAR_COUNT = 5
const SENSITIVITY = [0.6, 0.8, 1.0, 0.8, 0.6]

export const WaveAnimation = forwardRef<WaveAnimationHandle, Props>(({ active }, ref) => {
  const barRefs = useRef<(HTMLDivElement | null)[]>([])

  useImperativeHandle(ref, () => ({
    setVolume(vol: number) {
      barRefs.current.forEach((bar, i) => {
        if (!bar) return
        bar.style.height = vol > 0 ? `${8 + vol * SENSITIVITY[i] * 32}px` : '8px'
      })
    }
  }))

  return (
    <div className={`wave-container ${active ? 'wave-active' : 'wave-idle'}`}>
      {Array.from({ length: BAR_COUNT }).map((_, i) => (
        <div
          key={i}
          className="wave-bar"
          ref={el => { barRefs.current[i] = el }}
          style={{ animationDelay: `${i * 0.1}s` }}
        />
      ))}
    </div>
  )
})

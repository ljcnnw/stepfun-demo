import React, { useEffect, useRef } from 'react'
import type { ChatMessage } from '../types/asr'
import './TranscriptPanel.css'

interface Props {
  messages: ChatMessage[]
  isSpeaking: boolean
}

const AudioWave: React.FC = () => (
  <div className="audio-wave">
    {Array.from({ length: 4 }).map((_, i) => (
      <span key={i} className="audio-wave-bar" style={{ animationDelay: `${i * 0.12}s` }} />
    ))}
  </div>
)

export const TranscriptPanel: React.FC<Props> = ({ messages }) => {
  const bottomRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' })
  }, [messages])

  if (messages.length === 0) {
    return (
      <div className="chat-panel chat-empty">
        <span>点击接听后开始说话…</span>
      </div>
    )
  }

  return (
    <div className="chat-panel">
      {messages.map((msg) => {
        const displayText = msg.final !== null ? msg.final : msg.interim
        const isStreaming = msg.final === null
        return (
          <div key={msg.id} className={`bubble-row ${msg.role}`}>
            {msg.role === 'assistant' && (
              <div className="avatar assistant-avatar">AI</div>
            )}
            <div className={`bubble ${msg.role} ${isStreaming ? 'streaming' : ''}`}>
              <span>{displayText}</span>
              {isStreaming && <span className="cursor">▌</span>}
              {msg.role === 'assistant' && msg.playing && <AudioWave />}
            </div>
            {msg.role === 'user' && (
              <div className="avatar user-avatar">我</div>
            )}
          </div>
        )
      })}
      <div ref={bottomRef} />
    </div>
  )
}

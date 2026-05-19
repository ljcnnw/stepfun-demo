export interface AsrDeltaEvent {
  type: 'conversation.item.input_audio_transcription.delta'
  event_id: string
  item_id: string
  content_index: number
  text: string
  start_time: number
  end_time: number
}

export interface AsrCompletedEvent {
  type: 'conversation.item.input_audio_transcription.completed'
  event_id: string
  item_id: string
  content_index: number
  transcript: string
}

export interface VadSpeechStartedEvent {
  type: 'input_audio_buffer.speech_started'
}

export interface TtsTextDeltaEvent {
  type: 'tts.text.delta'
  text: string
}

export interface TtsAudioDeltaEvent {
  type: 'tts.audio.delta'
  audio: string
}

export interface TtsAudioDoneEvent {
  type: 'tts.audio.done'
}

export interface LlmTextDeltaEvent {
  type: 'llm.text.delta'
  text: string
}

export interface LlmTextDoneEvent {
  type: 'llm.text.done'
}

export interface TtsInterruptedEvent {
  type: 'tts.interrupted'
}

export type ServerEvent =
  | AsrDeltaEvent
  | AsrCompletedEvent
  | VadSpeechStartedEvent
  | TtsTextDeltaEvent
  | TtsAudioDeltaEvent
  | TtsAudioDoneEvent
  | TtsInterruptedEvent
  | LlmTextDeltaEvent
  | LlmTextDoneEvent

export type MessageRole = 'user' | 'assistant'

export interface ChatMessage {
  id: string
  role: MessageRole
  // 流式阶段的临时文本
  interim: string
  // 最终文本（completed / tts.audio.done 后设置）
  final: string | null
  // 是否正在播放 TTS
  playing: boolean
}

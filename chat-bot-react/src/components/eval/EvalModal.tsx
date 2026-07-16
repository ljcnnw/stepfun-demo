import { useEffect, useRef, type ReactNode } from 'react'
import './EvalModal.css'

type EvalModalVariant = 'drawer' | 'fullscreen'

interface EvalModalProps {
  title: string
  variant: EvalModalVariant
  onClose: () => void
  children: ReactNode
  actions?: ReactNode
  onBack?: () => void
}

export function EvalModal({ title, variant, onClose, children, actions, onBack }: EvalModalProps) {
  const modalRef = useRef<HTMLElement | null>(null)
  const closeButtonRef = useRef<HTMLButtonElement | null>(null)
  const previousFocusedElementRef = useRef<HTMLElement | null>(null)
  const onCloseRef = useRef(onClose)

  useEffect(() => {
    onCloseRef.current = onClose
  }, [onClose])

  useEffect(() => {
    const previousOverflow = document.body.style.overflow
    previousFocusedElementRef.current = document.activeElement instanceof HTMLElement ? document.activeElement : null

    const getFocusableElements = () => {
      if (!modalRef.current) return []
      return Array.from(modalRef.current.querySelectorAll<HTMLElement>(
        'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])',
      )).filter(element => !element.hasAttribute('hidden'))
    }

    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        event.preventDefault()
        onCloseRef.current()
        return
      }

      if (event.key !== 'Tab') return
      const focusableElements = getFocusableElements()
      if (focusableElements.length === 0) {
        event.preventDefault()
        modalRef.current?.focus()
        return
      }

      const first = focusableElements[0]
      const last = focusableElements[focusableElements.length - 1]
      if (event.shiftKey && document.activeElement === first) {
        event.preventDefault()
        last.focus()
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault()
        first.focus()
      }
    }

    document.body.style.overflow = 'hidden'
    document.addEventListener('keydown', onKeyDown)
    window.requestAnimationFrame(() => closeButtonRef.current?.focus())

    return () => {
      document.body.style.overflow = previousOverflow
      document.removeEventListener('keydown', onKeyDown)
      window.requestAnimationFrame(() => {
        previousFocusedElementRef.current?.focus()
      })
    }
  }, [])

  return (
    <div
      className="eval-modal-backdrop"
      onMouseDown={(event) => {
        if (event.target === event.currentTarget) onClose()
      }}
    >
      <section ref={modalRef} className={`eval-modal eval-modal-${variant}`} role="dialog" aria-modal="true" aria-label={title} tabIndex={-1}>
        <header className="eval-modal-header">
          <div className="eval-modal-heading">
            {onBack ? <button type="button" className="eval-modal-back" onClick={onBack}>返回</button> : null}
            <h2>{title}</h2>
          </div>
          <div className="eval-modal-actions">
            {actions}
            <button ref={closeButtonRef} type="button" className="eval-modal-close" onClick={onClose} aria-label="关闭">x</button>
          </div>
        </header>
        <div className="eval-modal-body">{children}</div>
      </section>
    </div>
  )
}

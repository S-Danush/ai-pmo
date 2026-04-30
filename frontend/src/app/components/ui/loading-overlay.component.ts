import { CommonModule } from '@angular/common';
import { Component, Input } from '@angular/core';

/** Full-viewport loading shell — use on any page while data is in flight. */
@Component({
  selector: 'app-loading-overlay',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div class="app-loading-root" *ngIf="active" role="status" aria-live="polite" aria-busy="true">
      <div class="app-loading-backdrop"></div>
      <div class="app-loading-panel">
        <div class="app-loading-spinner" aria-hidden="true"></div>
        <span class="app-loading-label">{{ label }}</span>
      </div>
    </div>
  `,
  styles: `
    .app-loading-root {
      position: fixed;
      inset: 0;
      z-index: 9999;
      display: flex;
      align-items: center;
      justify-content: center;
      pointer-events: auto;
    }
    .app-loading-backdrop {
      position: absolute;
      inset: 0;
      background: var(--backdrop-scrim);
      backdrop-filter: blur(6px);
    }
    .app-loading-panel {
      position: relative;
      display: flex;
      flex-direction: column;
      align-items: center;
      gap: 1rem;
      padding: 1.35rem 1.85rem;
      border-radius: 14px;
      background: var(--card-bg);
      border: 1px solid var(--border);
      box-shadow: var(--shadow-lg);
    }
    .app-loading-spinner {
      width: 38px;
      height: 38px;
      border-radius: 50%;
      border: 3px solid color-mix(in srgb, var(--text) 14%, transparent);
      border-top-color: var(--accent-1);
      animation: app-loading-spin 0.72s linear infinite;
    }
    .app-loading-label {
      font-size: 0.9rem;
      font-weight: 600;
      color: var(--text);
      letter-spacing: 0.03em;
    }
    @keyframes app-loading-spin {
      to {
        transform: rotate(360deg);
      }
    }
  `,
})
export class LoadingOverlayComponent {
  @Input() active = false;
  @Input() label = 'Loading…';
}

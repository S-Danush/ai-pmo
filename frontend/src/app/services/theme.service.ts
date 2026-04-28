import { Injectable, signal } from '@angular/core';

export type ThemeMode = 'dark' | 'light';

const STORAGE_KEY = 'aipmo_theme';

@Injectable({
  providedIn: 'root',
})
export class ThemeService {
  readonly mode = signal<ThemeMode>(this.readInitial());

  constructor() {
    this.apply(this.mode());
  }

  toggle(): void {
    const next: ThemeMode = this.mode() === 'dark' ? 'light' : 'dark';
    this.setMode(next);
  }

  setMode(m: ThemeMode): void {
    this.mode.set(m);
    try {
      localStorage.setItem(STORAGE_KEY, m);
    } catch {
      /* ignore */
    }
    this.apply(m);
  }

  private readInitial(): ThemeMode {
    try {
      const s = localStorage.getItem(STORAGE_KEY);
      if (s === 'light' || s === 'dark') {
        return s;
      }
    } catch {
      /* ignore */
    }
    return 'dark';
  }

  private apply(m: ThemeMode): void {
    const root = document.documentElement;
    root.setAttribute('data-theme', m);
    root.classList.toggle('theme-light', m === 'light');
    root.classList.toggle('theme-dark', m === 'dark');
  }
}

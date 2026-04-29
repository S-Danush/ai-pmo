import { Injectable, signal } from '@angular/core';

export type ThemeMode = 'light' | 'dark';

const STORAGE_KEY = 'aipmo-theme';

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
    this.mode.set(next);
    localStorage.setItem(STORAGE_KEY, next);
    this.apply(next);
  }

  private readInitial(): ThemeMode {
    const stored = localStorage.getItem(STORAGE_KEY) as ThemeMode | null;
    if (stored === 'light' || stored === 'dark') {
      return stored;
    }
    return 'dark';
  }

  private apply(m: ThemeMode): void {
    if (typeof document === 'undefined') {
      return;
    }
    const root = document.documentElement;
    root.removeAttribute('data-theme');
    root.classList.remove('theme-light', 'theme-dark');
    if (m === 'light') {
      root.setAttribute('data-theme', 'light');
      root.classList.add('theme-light');
    } else {
      root.setAttribute('data-theme', 'dark');
      root.classList.add('theme-dark');
    }
  }
}

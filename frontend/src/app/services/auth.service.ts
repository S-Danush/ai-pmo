import { Injectable } from '@angular/core';

const STORAGE_KEY = 'aipmo_auth';

@Injectable({
  providedIn: 'root',
})
export class AuthService {
  isLoggedIn(): boolean {
    try {
      return localStorage.getItem(STORAGE_KEY) === 'true';
    } catch {
      return false;
    }
  }

  /** Demo auth: username `admin`, password `1234`. */
  login(username: string, password: string): boolean {
    const ok = username === 'admin' && password === '1234';
    if (ok) {
      try {
        localStorage.setItem(STORAGE_KEY, 'true');
      } catch {
        /* ignore */
      }
    }
    return ok;
  }

  logout(): void {
    try {
      localStorage.removeItem(STORAGE_KEY);
    } catch {
      /* ignore */
    }
  }
}

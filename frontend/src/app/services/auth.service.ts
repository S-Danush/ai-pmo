import { Injectable, signal } from '@angular/core';

const STORAGE_KEY = 'aipmo-auth';

@Injectable({
  providedIn: 'root',
})
export class AuthService {
  readonly isLoggedIn = signal(this.readStored());

  login(username: string, password: string): boolean {
    if (username.trim() === 'admin' && password === '1234') {
      sessionStorage.setItem(STORAGE_KEY, '1');
      this.isLoggedIn.set(true);
      return true;
    }
    return false;
  }

  logout(): void {
    sessionStorage.removeItem(STORAGE_KEY);
    this.isLoggedIn.set(false);
  }

  private readStored(): boolean {
    return sessionStorage.getItem(STORAGE_KEY) === '1';
  }
}

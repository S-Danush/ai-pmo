import { CommonModule } from '@angular/common';
import { Component } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { AuthService } from '../../services/auth.service';

@Component({
  selector: 'app-login',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './login.component.html',
  styleUrl: './login.component.css',
})
export class LoginComponent {
  username = '';
  password = '';
  error: string | null = null;
  submitting = false;

  constructor(
    private readonly auth: AuthService,
    private readonly router: Router,
  ) {}

  submit(): void {
    this.error = null;
    this.submitting = true;
    const ok = this.auth.login(this.username.trim(), this.password);
    this.submitting = false;
    if (ok) {
      void this.router.navigateByUrl('/');
    } else {
      this.error = 'Invalid username or password.';
    }
  }
}

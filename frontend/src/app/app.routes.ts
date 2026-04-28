import { Routes } from '@angular/router';
import { DashboardComponent } from './components/dashboard/dashboard.component';
import { LoginComponent } from './components/login/login.component';
import { ShellComponent } from './components/shell/shell.component';
import { authGuard } from './guards/auth.guard';
import { loginGuard } from './guards/login.guard';

export const routes: Routes = [
  { path: 'login', canActivate: [loginGuard], component: LoginComponent },
  {
    path: '',
    canActivate: [authGuard],
    component: ShellComponent,
    children: [{ path: '', component: DashboardComponent }],
  },
];

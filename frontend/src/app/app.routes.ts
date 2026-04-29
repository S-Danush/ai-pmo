import { Routes } from '@angular/router';
import { AiAgentComponent } from './components/ai-agent/ai-agent.component';
import { DashboardComponent } from './components/dashboard/dashboard.component';
import { LoginComponent } from './components/login/login.component';
import { ShellComponent } from './components/shell/shell.component';
import { TeamAnalyticsComponent } from './components/team-analytics/team-analytics.component';
import { authGuard } from './guards/auth.guard';
import { loginGuard } from './guards/login.guard';

export const routes: Routes = [
  { path: 'login', canActivate: [loginGuard], component: LoginComponent },
  {
    path: '',
    canActivate: [authGuard],
    component: ShellComponent,
    children: [
      { path: '', component: DashboardComponent, data: { section: 'overview' } },
      { path: 'tickets', component: DashboardComponent, data: { section: 'tickets' } },
      { path: 'bottlenecks', component: DashboardComponent, data: { section: 'bottlenecks' } },
      { path: 'team', component: TeamAnalyticsComponent },
      { path: 'agent', component: AiAgentComponent },
    ],
  },
];

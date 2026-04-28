import { CommonModule } from '@angular/common';
import { Component, HostListener } from '@angular/core';
import { Router, RouterOutlet } from '@angular/router';
import {
  DashboardStateService,
  MainTab,
  SidebarSection,
} from '../../services/dashboard-state.service';
import { ThemeService } from '../../services/theme.service';
import { AuthService } from '../../services/auth.service';

@Component({
  selector: 'app-shell',
  standalone: true,
  imports: [CommonModule, RouterOutlet],
  templateUrl: './shell.component.html',
  styleUrl: './shell.component.css',
})
export class ShellComponent {
  readonly tabs: { id: MainTab; label: string }[] = [
    { id: 'dashboard', label: 'Dashboard' },
    { id: 'insights', label: 'Insights' },
    { id: 'suggestions', label: 'Suggestions' },
    { id: 'notifications', label: 'Notifications' },
  ];

  readonly sidebarItems: { id: SidebarSection; label: string; icon: string }[] = [
    { id: 'overview', label: 'Overview', icon: '◇' },
    { id: 'risk', label: 'Risk View', icon: '⚑' },
    { id: 'bottlenecks', label: 'Bottlenecks', icon: '◎' },
    { id: 'team', label: 'Team View', icon: '◉' },
    { id: 'history', label: 'Run History', icon: '↻' },
  ];

  userMenuOpen = false;
  notifOpen = false;

  constructor(
    readonly dash: DashboardStateService,
    readonly theme: ThemeService,
    private readonly auth: AuthService,
    private readonly router: Router,
  ) {}

  selectTab(t: MainTab): void {
    this.dash.setTab(t);
  }

  selectSidebar(s: SidebarSection): void {
    this.dash.setSidebarSection(s);
  }

  toggleTheme(): void {
    this.theme.toggle();
  }

  logout(): void {
    this.auth.logout();
    this.userMenuOpen = false;
    void this.router.navigateByUrl('/login');
  }

  toggleUserMenu(): void {
    this.userMenuOpen = !this.userMenuOpen;
    if (this.userMenuOpen) {
      this.notifOpen = false;
    }
  }

  toggleNotifDropdown(): void {
    this.notifOpen = !this.notifOpen;
    if (this.notifOpen) {
      this.userMenuOpen = false;
    }
  }

  @HostListener('document:click', ['$event'])
  onDocClick(ev: MouseEvent): void {
    const t = ev.target as HTMLElement;
    if (!t.closest('.user-wrap') && !t.closest('.notif-wrap')) {
      this.userMenuOpen = false;
      this.notifOpen = false;
    }
  }
}

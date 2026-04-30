import { CommonModule, NgSwitch, NgSwitchCase } from '@angular/common';
import { Component, HostListener, OnDestroy, OnInit } from '@angular/core';
import { ActivatedRoute, NavigationEnd, Router, RouterOutlet } from '@angular/router';
import { filter, Subscription } from 'rxjs';
import {
  DashboardStateService,
  SidebarSection,
} from '../../services/dashboard-state.service';
import { ThemeService } from '../../services/theme.service';
import { AuthService } from '../../services/auth.service';

@Component({
  selector: 'app-shell',
  standalone: true,
  imports: [CommonModule, RouterOutlet, NgSwitch, NgSwitchCase],
  templateUrl: './shell.component.html',
  styleUrl: './shell.component.css',
})
export class ShellComponent implements OnInit, OnDestroy {
  readonly sidebarItems: { id: SidebarSection; label: string; route: string | null }[] = [
    { id: 'overview', label: 'Overview', route: null },
    { id: 'delivery-analytics', label: 'Delivery', route: 'delivery-analytics' },
    { id: 'agent-actions', label: 'Agent actions', route: 'agent-actions' },
    { id: 'tickets', label: 'Jira Tickets', route: 'tickets' },
    { id: 'bottlenecks', label: 'Bottlenecks', route: 'bottlenecks' },
    { id: 'team-analytics', label: 'Git Analytics', route: 'team' },
    { id: 'ai-agent', label: 'AI Agent', route: 'agent' },
  ];

  userMenuOpen = false;
  notifOpen = false;
  private navSub?: Subscription;

  constructor(
    readonly dash: DashboardStateService,
    readonly theme: ThemeService,
    private readonly auth: AuthService,
    private readonly router: Router,
    private readonly route: ActivatedRoute,
  ) {}

  ngOnInit(): void {
    this.syncSidebarFromUrl(this.router.url);
    this.navSub = this.router.events
      .pipe(filter((e): e is NavigationEnd => e instanceof NavigationEnd))
      .subscribe((e) => this.syncSidebarFromUrl(e.urlAfterRedirects));
  }

  ngOnDestroy(): void {
    this.navSub?.unsubscribe();
  }

  private syncSidebarFromUrl(url: string): void {
    const path = url.split('?')[0]?.replace(/\/$/, '') ?? '';
    const segments = path.split('/').filter(Boolean);
    const last = segments[segments.length - 1] ?? '';

    if (last === 'tickets') {
      this.dash.setSidebarSection('tickets');
      return;
    }
    if (last === 'delivery-analytics') {
      this.dash.setSidebarSection('delivery-analytics');
      return;
    }
    if (last === 'agent-actions') {
      this.dash.setSidebarSection('agent-actions');
      return;
    }
    if (last === 'bottlenecks') {
      this.dash.setSidebarSection('bottlenecks');
      return;
    }
    if (last === 'team') {
      this.dash.setSidebarSection('team-analytics');
      return;
    }
    if (last === 'agent') {
      this.dash.setSidebarSection('ai-agent');
      return;
    }
    if (!last || last === '') {
      this.dash.setSidebarSection('overview');
    }
  }

  selectSidebar(s: SidebarSection): void {
    const item = this.sidebarItems.find((i) => i.id === s);
    this.dash.setSidebarSection(s);
    if (item?.route) {
      void this.router.navigate([item.route], { relativeTo: this.route });
      return;
    }
    void this.router.navigate([''], { relativeTo: this.route });
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

  mainWorkspaceClass(): string {
    const s = this.dash.sidebarSection();
    if (s === 'team-analytics') {
      return 'ws-team-analytics';
    }
    if (s === 'ai-agent') {
      return 'ws-ai-agent';
    }
    return 'ws-' + s;
  }

  toggleNotifDropdown(): void {
    this.notifOpen = !this.notifOpen;
    if (this.notifOpen) {
      this.userMenuOpen = false;
    }
  }

  dismissPending(ticketId: string, ev: MouseEvent): void {
    ev.stopPropagation();
    this.dash.removePendingAction(ticketId);
  }

  dismissNotified(ticketId: string, ev: MouseEvent): void {
    ev.stopPropagation();
    this.dash.removeNotifiedPreview(ticketId);
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

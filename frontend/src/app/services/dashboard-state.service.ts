import { Injectable, computed, signal } from '@angular/core';

export type MainTab = 'dashboard' | 'insights' | 'suggestions' | 'notifications';

export type SidebarSection = 'overview' | 'risk' | 'bottlenecks' | 'team' | 'history';

export interface NotifiedPreviewRow {
  ticketId: string;
  title: string;
  at: string | null;
}

@Injectable({
  providedIn: 'root',
})
export class DashboardStateService {
  readonly activeTab = signal<MainTab>('dashboard');
  readonly sidebarCollapsed = signal(false);
  readonly sidebarSection = signal<SidebarSection>('overview');

  /** Navbar bell: recently notified tickets (newest first). */
  readonly notifiedPreview = signal<NotifiedPreviewRow[]>([]);

  readonly notificationBellCount = computed(() => this.notifiedPreview().length);

  setTab(t: MainTab): void {
    this.activeTab.set(t);
  }

  toggleSidebar(): void {
    this.sidebarCollapsed.update((c) => !c);
  }

  setSidebarSection(s: SidebarSection): void {
    this.sidebarSection.set(s);
  }

  syncNotifiedFromTickets(rows: NotifiedPreviewRow[]): void {
    this.notifiedPreview.set(rows.slice(0, 12));
  }

  prependNotified(row: NotifiedPreviewRow): void {
    const cur = this.notifiedPreview().filter((r) => r.ticketId !== row.ticketId);
    this.notifiedPreview.set([row, ...cur].slice(0, 12));
  }
}

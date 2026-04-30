import { Injectable, computed, signal } from '@angular/core';

export type MainTab = 'dashboard';

export type SidebarSection =
  | 'overview'
  | 'delivery-analytics'
  | 'agent-actions'
  | 'tickets'
  | 'bottlenecks'
  | 'team-analytics'
  | 'ai-agent';

export interface NotifiedPreviewRow {
  ticketId: string;
  title: string;
  at: string | null;
}

export interface PendingActionRow {
  ticketId: string;
  title: string;
  action: string;
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

  /** Suggested next steps (from API suggestions). */
  readonly pendingActions = signal<PendingActionRow[]>([]);

  readonly notificationBellCount = computed(
    () => this.notifiedPreview().length + this.pendingActions().length,
  );

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

  syncPendingFromSuggestions(rows: PendingActionRow[]): void {
    this.pendingActions.set(rows.slice(0, 8));
  }

  removePendingAction(ticketId: string): void {
    this.pendingActions.update((rows) => rows.filter((r) => r.ticketId !== ticketId));
  }

  removeNotifiedPreview(ticketId: string): void {
    this.notifiedPreview.update((rows) => rows.filter((r) => r.ticketId !== ticketId));
  }
}

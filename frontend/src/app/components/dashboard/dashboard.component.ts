import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnInit } from '@angular/core';
import {
  AgentRunResponse,
  ApiService,
  ProjectHealth,
  ProjectSummary,
  Ticket,
} from '../../services/api.service';

@Component({
  selector: 'app-dashboard',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './dashboard.component.html',
  styleUrl: './dashboard.component.css',
})
export class DashboardComponent implements OnInit {
  tickets: Ticket[] = [];
  projectSummary: ProjectSummary | null = null;
  projectHealth: ProjectHealth | null = null;
  loading = false;
  error: string | null = null;
  useSimulation = false;

  filterRisk: string = '';
  /** Use "__UNASSIGNED__" to filter tickets with no owner. */
  filterAssignee: string = '';
  filterStatus: string = '';
  filterAging: string = '';

  readonly skeletonRows = [0, 1, 2, 3, 4];
  /** Display order: worst → unassigned → healthy. */
  readonly viewGroupOrder: Array<'BLOCKED' | 'AT_RISK' | 'UNASSIGNED' | 'HEALTHY'> = [
    'BLOCKED',
    'AT_RISK',
    'UNASSIGNED',
    'HEALTHY',
  ];

  constructor(private readonly api: ApiService) {}

  ngOnInit(): void {
    this.loadDashboard();
  }

  loadDashboard(): void {
    this.error = null;
    this.api.getInsights(this.useSimulation).subscribe({
      next: (r: AgentRunResponse) => this.applyRunResponse(r),
      error: (e: unknown) => (this.error = this.errMessage(e, 'Failed to load dashboard')),
    });
  }

  runAgent(): void {
    this.loading = true;
    this.error = null;
    this.api.runAgent(this.useSimulation).subscribe({
      next: (r: AgentRunResponse) => {
        this.applyRunResponse(r);
        this.loading = false;
      },
      error: (e: unknown) => {
        this.loading = false;
        this.error = this.errMessage(e, 'Agent run failed');
      },
    });
  }

  onSimulationChange(enabled: boolean): void {
    this.useSimulation = enabled;
    this.loadDashboard();
  }

  clearFilters(): void {
    this.filterRisk = '';
    this.filterAssignee = '';
    this.filterStatus = '';
    this.filterAging = '';
  }

  private applyRunResponse(r: AgentRunResponse): void {
    this.tickets = r.tickets ?? [];
    this.projectSummary = r.projectSummary ?? null;
    this.projectHealth = r.projectHealth ?? null;
  }

  private errMessage(e: unknown, fallback: string): string {
    if (e instanceof HttpErrorResponse) {
      if (typeof e.error === 'string' && e.error.length) {
        return e.error;
      }
      if (e.status === 0) {
        return 'Network error — is the backend running on port 8080?';
      }
      return e.message || fallback;
    }
    return e instanceof Error ? e.message : fallback;
  }

  statusClass(): 'health-red' | 'health-amber' | 'health-green' {
    const s = this.projectSummary?.status;
    if (s === 'RED') {
      return 'health-red';
    }
    if (s === 'AMBER') {
      return 'health-amber';
    }
    return 'health-green';
  }

  healthLabel(): string {
    switch (this.projectSummary?.status) {
      case 'RED':
        return 'At risk — critical delay or stuck work';
      case 'AMBER':
        return 'Watch — delays need attention';
      default:
        return 'On track';
    }
  }

  trendLabel(): string | null {
    if (this.projectSummary?.prDataAvailable === false) {
      return null;
    }
    const p = this.projectSummary?.prDelayTrendPercent;
    if (p == null || Number.isNaN(p)) {
      return null;
    }
    const dir = p >= 0 ? 'increased' : 'decreased';
    return `PR cycle vs reference baseline ${dir} by ${Math.abs(Math.round(p))}%`;
  }

  dataQualityBadge(): string | null {
    const q = this.projectSummary?.dataQuality;
    if (!q) {
      return null;
    }
    switch (q) {
      case 'HIGH':
        return 'Real Data';
      case 'PARTIAL':
        return 'Partial Data';
      case 'MOCK':
        return 'Mock Data';
      default:
        return null;
    }
  }

  dataQualityBadgeClass(): string {
    const q = this.projectSummary?.dataQuality ?? 'MOCK';
    return `dq-badge dq-${q.toLowerCase()}`;
  }

  prDataMissing(): boolean {
    if (this.projectSummary?.prDataAvailable === false) {
      return true;
    }
    return this.tickets.some((t) => (t.flags ?? []).includes('PR_DATA_MISSING'));
  }

  delayEstimateLabel(): string | null {
    const d = this.projectSummary?.estimatedDelayDays;
    if (d == null || d <= 0) {
      return null;
    }
    return `Est. excess dwell: ~${d.toFixed(1)} day(s) beyond 24h threshold`;
  }

  /** Tickets after client-side filters (assignee, risk, status, aging). */
  visibleTickets(): Ticket[] {
    return this.tickets.filter((t) => {
      if (this.filterAssignee) {
        const a = (t.assignee ?? '').toLowerCase();
        if (this.filterAssignee === '__UNASSIGNED__') {
          if (a && a !== 'unassigned') {
            return false;
          }
        } else if (a !== this.filterAssignee.toLowerCase()) {
          return false;
        }
      }
      if (this.filterRisk) {
        if ((t.deliveryRisk ?? '') !== this.filterRisk) {
          return false;
        }
      }
      if (this.filterStatus) {
        const ds = t.displayStatus?.trim() || t.status;
        if (ds !== this.filterStatus) {
          return false;
        }
      }
      if (this.filterAging) {
        if ((t.agingBucket ?? '') !== this.filterAging) {
          return false;
        }
      }
      return true;
    });
  }

  ticketsInGroup(g: 'BLOCKED' | 'AT_RISK' | 'HEALTHY' | 'UNASSIGNED'): Ticket[] {
    return this.visibleTickets().filter((t) => (t.viewGroup ?? '') === g);
  }

  uniqueAssignees(): { value: string; label: string }[] {
    const s = new Set<string>();
    for (const t of this.tickets) {
      if (!t.assignee || t.assignee.toLowerCase() === 'unassigned') {
        s.add('__UNASSIGNED__');
      } else {
        s.add(t.assignee);
      }
    }
    const out: { value: string; label: string }[] = [];
    for (const v of Array.from(s).sort((a, b) => a.localeCompare(b))) {
      if (v === '__UNASSIGNED__') {
        out.push({ value: v, label: 'Unassigned ⚠️' });
      } else {
        out.push({ value: v, label: v });
      }
    }
    return out;
  }

  uniqueDisplayStatuses(): string[] {
    const s = new Set<string>();
    for (const t of this.tickets) {
      s.add(t.displayStatus?.trim() || t.status);
    }
    return Array.from(s).sort((a, b) => a.localeCompare(b));
  }

  displayStatus(t: Ticket): string {
    return (t.displayStatus?.trim() || t.status) || '—';
  }

  displayAssignee(t: Ticket): string {
    const a = t.assignee?.trim();
    if (!a || a.toLowerCase() === 'unassigned') {
      return 'Unassigned ⚠️';
    }
    return a;
  }

  ticketTitle(t: Ticket): string {
    if (t.summary && t.summary.trim() && t.summary !== '(no summary)') {
      return t.summary;
    }
    return '—';
  }

  displayProgress(t: Ticket): string {
    if (t.progressLabel?.length) {
      return t.progressLabel;
    }
    if (t.timeInState > 168) {
      return 'Stuck';
    }
    if (t.timeInState > 72) {
      return 'Delayed';
    }
    return 'On track';
  }

  riskPillClass(t: Ticket): string {
    const r = (t.deliveryRisk ?? '').toUpperCase();
    if (r === 'HIGH') {
      return 'pill-risk-high';
    }
    if (r === 'MEDIUM') {
      return 'pill-risk-med';
    }
    return 'pill-risk-low';
  }

  riskLabel(t: Ticket): string {
    const r = t.deliveryRisk;
    if (r === 'HIGH') {
      return 'High';
    }
    if (r === 'MEDIUM') {
      return 'Medium';
    }
    if (r === 'LOW') {
      return 'Low';
    }
    return '—';
  }

  groupHeading(g: 'BLOCKED' | 'AT_RISK' | 'HEALTHY' | 'UNASSIGNED'): string {
    switch (g) {
      case 'BLOCKED':
        return 'Blocked / stuck issues';
      case 'AT_RISK':
        return 'At risk';
      case 'HEALTHY':
        return 'Healthy';
      case 'UNASSIGNED':
        return 'Unassigned';
      default:
        return g;
    }
  }

  groupIcon(g: 'BLOCKED' | 'AT_RISK' | 'HEALTHY' | 'UNASSIGNED'): string {
    switch (g) {
      case 'BLOCKED':
        return '🔴';
      case 'AT_RISK':
        return '🟡';
      case 'HEALTHY':
        return '🟢';
      case 'UNASSIGNED':
        return '⚪';
      default:
        return '';
    }
  }

  insightTickets(): Ticket[] {
    return this.tickets.filter(
      (t) =>
        !!(t.insight || t.rootCause || t.nudge) &&
        (t.severity === 'HIGH' || t.severity === 'MEDIUM'),
    );
  }

  /** Legacy hook — kept for any template references */
  flagDisplays(t: Ticket): string[] {
    if (t.flagSummary?.length) {
      return t.flagSummary
        .split(',')
        .map((s) => s.trim())
        .filter((s) => s.length);
    }
    return (t.flags ?? [])
      .map((f) => {
        const m: Record<string, string> = {
          STUCK: 'Needs attention',
          CRITICAL_STUCK: 'High risk',
          PR_DELAY: 'Slow PR cycle',
          BOUNCING: 'Status churn',
          TREND_SPIKE: 'Dwell well above team average',
          SLOWDOWN: 'PR merge well above team average',
        };
        return m[f] ?? '';
      })
      .filter((s) => s.length);
  }

  trendClass(t: Ticket): string {
    const x = (t.trendIndicator ?? '').toUpperCase();
    if (x === 'UP') {
      return 'trend-up';
    }
    if (x === 'DOWN') {
      return 'trend-down';
    }
    return 'trend-stable';
  }

  severityClass(sev: string | null | undefined): string {
    const u = (sev ?? '').toUpperCase();
    if (u === 'HIGH') {
      return 'sev-high';
    }
    if (u === 'MEDIUM') {
      return 'sev-medium';
    }
    return 'sev-low';
  }
}

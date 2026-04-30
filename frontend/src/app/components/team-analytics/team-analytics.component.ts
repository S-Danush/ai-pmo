import { CommonModule } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnInit } from '@angular/core';
import { FormsModule } from '@angular/forms';
import {
  ApiEnvelope,
  ApiService,
  GitActivityPerMember,
  TeamAnalyticsResponse,
  TeamMemberAnalytics,
  WorkloadBar,
} from '../../services/api.service';
import { LoadingOverlayComponent } from '../ui/loading-overlay.component';

@Component({
  selector: 'app-team-analytics',
  standalone: true,
  imports: [CommonModule, FormsModule, LoadingOverlayComponent],
  templateUrl: './team-analytics.component.html',
  styleUrl: './team-analytics.component.css',
})
export class TeamAnalyticsComponent implements OnInit {
  data: TeamAnalyticsResponse | null = null;
  loading = true;
  error: string | null = null;

  filterAssignee = '';
  filterPerformance = 'all';
  filterWorkload = 'all';

  /** Bar chart uses same rates as the activity table: day / week / month. */
  commitBarPeriod: 'day' | 'week' | 'month' = 'week';

  constructor(private readonly api: ApiService) {}

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.loading = true;
    this.error = null;
    this.api.getTeamAnalytics().subscribe({
      next: (d) => {
        this.data = d;
        this.loading = false;
      },
      error: (e: unknown) => {
        this.loading = false;
        this.error = this.errMessage(e, 'Failed to load team analytics');
      },
    });
  }

  assigneeNameMatchesFilter(rowName: string, filter: string): boolean {
    const n = (rowName ?? '').trim().toLowerCase();
    const f = (filter ?? '').trim().toLowerCase();
    if (!f) {
      return true;
    }
    if (!n) {
      return false;
    }
    if (n.includes(f) || f.includes(n)) {
      return true;
    }
    const toks = f.split(/\s+/).filter((t) => t.length >= 2);
    return toks.length > 1 && toks.every((t) => n.includes(t));
  }

  filteredMembers(): TeamMemberAnalytics[] {
    const m = this.data?.members ?? [];
    return m.filter((row) => {
      if (this.filterAssignee && !this.assigneeNameMatchesFilter(row.name, this.filterAssignee)) {
        return false;
      }
      if (this.filterPerformance !== 'all' && row.performanceLevel !== this.filterPerformance) {
        return false;
      }
      if (this.filterWorkload !== 'all') {
        const w = this.workloadRow(row.name);
        if (!w || w.highlight !== this.filterWorkload) {
          return false;
        }
      }
      return true;
    });
  }

  workloadRow(name: string): WorkloadBar | undefined {
    return this.data?.workloadByAssignee.find((w) => w.assigneeName === name);
  }

  assigneeOptions(): string[] {
    return (this.data?.members ?? []).map((m) => m.name).sort();
  }

  commitsBarValue(g: GitActivityPerMember): number {
    switch (this.commitBarPeriod) {
      case 'day':
        return g.commitsPerDay;
      case 'month':
        return g.commitsPerMonth;
      case 'week':
      default:
        return g.commitsPerWeek;
    }
  }

  maxCommitsBar(): number {
    const rows = this.data?.gitActivityByMember ?? [];
    const vals = rows.map((x) => this.commitsBarValue(x));
    return Math.max(1, ...vals, 0);
  }

  commitsBarPeriodLabel(): string {
    switch (this.commitBarPeriod) {
      case 'day':
        return 'Average commits per day (same as table).';
      case 'month':
        return 'Average commits per month (same as table).';
      case 'week':
      default:
        return 'Average commits per week (same as table).';
    }
  }

  maxCommitsWeek(): number {
    const g = this.data?.gitActivityByMember ?? [];
    return Math.max(1, ...g.map((x) => x.commitsPerWeek));
  }

  gitFor(name: string): GitActivityPerMember | undefined {
    return this.data?.gitActivityByMember.find((x) => x.assigneeName === name);
  }

  maxWorkload(): number {
    const w = this.data?.workloadByAssignee ?? [];
    return Math.max(1, ...w.map((x) => x.activeTicketCount));
  }

  initials(name: string): string {
    const p = name.trim().split(/\s+/).filter(Boolean);
    if (p.length >= 2) {
      return (p[0][0] + p[p.length - 1][0]).toUpperCase();
    }
    return name.slice(0, 2).toUpperCase() || '?';
  }

  perfClass(level: string): string {
    switch (level) {
      case 'OVERLOADED':
        return 'perf-risk';
      case 'UNDERUTILIZED':
        return 'perf-under';
      case 'BALANCED':
      default:
        return 'perf-moderate';
    }
  }

  highlightClass(h: string): string {
    if (h === 'OVERLOADED') {
      return 'hl-over';
    }
    if (h === 'UNDERUTILIZED') {
      return 'hl-under';
    }
    return '';
  }

  private errMessage(e: unknown, fallback: string): string {
    if (e instanceof HttpErrorResponse) {
      const body = e.error;
      if (body && typeof body === 'object') {
        const env = body as ApiEnvelope<unknown>;
        if (env.error?.message) {
          return env.error.message;
        }
        const msg = (body as { message?: string }).message;
        if (typeof msg === 'string' && msg.length > 0) {
          return msg;
        }
      }
      return e.message ?? fallback;
    }
    return e instanceof Error ? e.message : fallback;
  }
}

import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HttpErrorResponse } from '@angular/common/http';
import { Component, HostListener, OnInit } from '@angular/core';
import { forkJoin } from 'rxjs';
import { DashboardStateService } from '../../services/dashboard-state.service';
import {
  AgentRunResponse,
  ApiService,
  DeliveryTrend,
  DeliveryTrendPoint,
  ManualNotifyResponse,
  ProjectHealth,
  ProjectSummary,
  Ticket,
  TicketSuggestion,
} from '../../services/api.service';

export type GroupByMode =
  | 'delivery'
  | 'status'
  | 'assignee'
  | 'risk'
  | 'bottleneck'
  | 'priority';

@Component({
  selector: 'app-dashboard',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './dashboard.component.html',
  styleUrl: './dashboard.component.css',
})
export class DashboardComponent implements OnInit {
  tickets: Ticket[] = [];
  suggestions: TicketSuggestion[] = [];
  projectSummary: ProjectSummary | null = null;
  projectHealth: ProjectHealth | null = null;
  deliveryTrend: DeliveryTrend | null = null;
  generatedAt: string | null = null;
  loading = false;
  error: string | null = null;

  /** Ticket ids currently sending to Teams (object for reliable change detection). */
  notifyingIds: Record<string, boolean> = {};
  toast: { message: string; kind: 'ok' | 'err' | 'warn' } | null = null;
  private toastTimer: ReturnType<typeof setTimeout> | null = null;

  /** Multi-select filters — empty array means “all”. */
  filterRisks: string[] = [];
  /** Use "__UNASSIGNED__" for unassigned. */
  filterAssignees: string[] = [];
  filterStatuses: string[] = [];
  filterPriorities: string[] = [];
  filterBottlenecks: string[] = [];

  groupByMode: GroupByMode = 'delivery';

  /** Side panel ticket detail */
  selectedTicket: Ticket | null = null;

  /** Run comparison: focus on latest vs previous snapshot */
  runView: 'current' | 'previous' = 'current';

  filtersExpanded = true;

  readonly skeletonRows = [0, 1, 2, 3, 4];
  /** Display order: worst → unassigned → healthy. */
  readonly viewGroupOrder: Array<'BLOCKED' | 'AT_RISK' | 'UNASSIGNED' | 'HEALTHY'> = [
    'BLOCKED',
    'AT_RISK',
    'UNASSIGNED',
    'HEALTHY',
  ];

  constructor(
    private readonly api: ApiService,
    readonly dash: DashboardStateService,
  ) {}

  ngOnInit(): void {
    this.loadDashboard();
  }

  @HostListener('document:keydown.escape')
  onEscape(): void {
    if (this.selectedTicket) {
      this.closeTicketPanel();
    }
  }

  activeFilterCount(): number {
    return (
      this.filterRisks.length +
      this.filterAssignees.length +
      this.filterStatuses.length +
      this.filterPriorities.length +
      this.filterBottlenecks.length
    );
  }

  loadDashboard(): void {
    this.error = null;
    forkJoin({
      insights: this.api.getInsights(),
      suggestions: this.api.getSuggestions(),
      trend: this.api.getDeliveryTrend(),
    }).subscribe({
      next: ({ insights, suggestions, trend }) => {
        this.applyRunResponse(insights);
        this.suggestions = suggestions ?? [];
        this.deliveryTrend = trend ?? null;
      },
      error: (e: unknown) => (this.error = this.errMessage(e, 'Failed to load dashboard')),
    });
  }

  runAgent(): void {
    this.loading = true;
    this.error = null;
    this.api.runAgent().subscribe({
      next: (r: AgentRunResponse) => {
        this.applyRunResponse(r);
        this.api.getSuggestions().subscribe({
          next: (s) => (this.suggestions = s ?? []),
          error: () => (this.suggestions = []),
        });
        this.api.getDeliveryTrend().subscribe({
          next: (t) => (this.deliveryTrend = t ?? null),
          error: () => {},
        });
        this.loading = false;
      },
      error: (e: unknown) => {
        this.loading = false;
        this.error = this.errMessage(e, 'Agent run failed');
      },
    });
  }

  clearFilters(): void {
    this.filterRisks = [];
    this.filterAssignees = [];
    this.filterStatuses = [];
    this.filterPriorities = [];
    this.filterBottlenecks = [];
  }

  private toggleIn(list: string[], value: string): string[] {
    return list.includes(value) ? list.filter((x) => x !== value) : [...list, value];
  }

  toggleRisk(value: string): void {
    this.filterRisks = this.toggleIn(this.filterRisks, value);
  }

  toggleAssignee(value: string): void {
    this.filterAssignees = this.toggleIn(this.filterAssignees, value);
  }

  toggleStatus(value: string): void {
    this.filterStatuses = this.toggleIn(this.filterStatuses, value);
  }

  togglePriority(value: string): void {
    this.filterPriorities = this.toggleIn(this.filterPriorities, value);
  }

  toggleBottleneck(value: string): void {
    this.filterBottlenecks = this.toggleIn(this.filterBottlenecks, value);
  }

  isOn(arr: string[], value: string): boolean {
    return arr.includes(value);
  }

  effectiveGroupBy(): GroupByMode {
    switch (this.dash.sidebarSection()) {
      case 'risk':
        return 'risk';
      case 'bottlenecks':
        return 'bottleneck';
      case 'team':
        return 'assignee';
      default:
        return this.groupByMode;
    }
  }

  sidebarOverridesGroup(): boolean {
    const s = this.dash.sidebarSection();
    return s === 'risk' || s === 'bottlenecks' || s === 'team';
  }

  normalizePriority(t: Ticket): string {
    const p = (t.priority ?? '').trim().toLowerCase();
    if (p === 'critical') {
      return 'CRITICAL';
    }
    if (p === 'high') {
      return 'HIGH';
    }
    if (p === 'medium') {
      return 'MEDIUM';
    }
    if (p === 'low') {
      return 'LOW';
    }
    return '—';
  }

  openTicketPanel(t: Ticket): void {
    this.selectedTicket = t;
  }

  closeTicketPanel(): void {
    this.selectedTicket = null;
  }

  /** Pair of snapshots to compare for the selected run window. */
  runPair(): { cur: DeliveryTrendPoint; prev: DeliveryTrendPoint } | null {
    const snaps = this.deliveryTrendSnapshots();
    if (this.runView === 'current') {
      if (snaps.length < 2) {
        return null;
      }
      const n = snaps.length;
      return { cur: snaps[n - 1]!, prev: snaps[n - 2]! };
    }
    if (snaps.length < 3) {
      return null;
    }
    const n = snaps.length;
    return { cur: snaps[n - 2]!, prev: snaps[n - 3]! };
  }

  runComparisonBullets(): { newRisks: string[]; resolved: string[] } {
    const pair = this.runPair();
    const newRisks: string[] = [];
    const resolved: string[] = [];
    if (!pair) {
      newRisks.push(
        this.runView === 'previous'
          ? 'Need at least three agent runs to compare the previous window.'
          : 'Run the agent at least twice to compare dwell and PR cycle between snapshots.',
      );
      return { newRisks, resolved };
    }
    const cur = pair.cur;
    const prev = pair.prev;
    if (cur.avgDwellHours > prev.avgDwellHours * 1.08) {
      newRisks.push(`Average dwell increased (${prev.avgDwellHours.toFixed(1)}h → ${cur.avgDwellHours.toFixed(1)}h).`);
    }
    if (cur.avgPrHours > prev.avgPrHours * 1.08) {
      newRisks.push(`PR cycle lengthened (${prev.avgPrHours.toFixed(1)}h → ${cur.avgPrHours.toFixed(1)}h).`);
    }
    if (cur.avgDwellHours < prev.avgDwellHours * 0.92) {
      resolved.push(`Flow improved: avg dwell down (${prev.avgDwellHours.toFixed(1)}h → ${cur.avgDwellHours.toFixed(1)}h).`);
    }
    if (cur.avgPrHours < prev.avgPrHours * 0.92) {
      resolved.push(`PR throughput improved (${prev.avgPrHours.toFixed(1)}h → ${cur.avgPrHours.toFixed(1)}h).`);
    }
    if (!newRisks.length && !resolved.length) {
      newRisks.push('No major swing between these runs — signals are stable.');
    }
    return { newRisks, resolved };
  }

  suggestionsTop(): TicketSuggestion[] {
    return this.suggestions.slice(0, 8);
  }

  notificationsHistoryRows(): Array<{ ticketId: string; title: string; at: string | null }> {
    return this.tickets
      .filter((t) => !!t.lastNotifiedAt)
      .sort((a, b) => {
        const ta = new Date(a.lastNotifiedAt!).getTime();
        const tb = new Date(b.lastNotifiedAt!).getTime();
        return tb - ta;
      })
      .map((t) => ({
        ticketId: t.id,
        title: this.ticketTitle(t),
        at: t.lastNotifiedAt ?? null,
      }));
  }

  lastNotifyGlobally(): string {
    let max = 0;
    for (const t of this.tickets) {
      if (!t.lastNotifiedAt) {
        continue;
      }
      const x = new Date(t.lastNotifiedAt).getTime();
      if (!Number.isNaN(x) && x > max) {
        max = x;
      }
    }
    if (!max) {
      return '—';
    }
    return new Date(max).toLocaleString();
  }

  flagTagLabel(code: string): string {
    const short: Record<string, string> = {
      BLOCKED: 'BLOCKED',
      PR_DELAY: 'PR DELAY',
      DEPENDENCY_RISK: 'DEPENDENCY',
      STUCK: 'DWELL',
      CRITICAL_STUCK: 'CRITICAL',
      BOUNCING: 'CHURN',
      TREND_SPIKE: 'SPIKE',
      SLOWDOWN: 'SLOWDOWN',
    };
    return short[code] ?? code.replace(/_/g, ' ');
  }

  private syncNotifiedPreview(): void {
    const rows = this.tickets
      .filter((t) => t.lastNotifiedAt)
      .sort((a, b) => {
        const ta = new Date(a.lastNotifiedAt!).getTime();
        const tb = new Date(b.lastNotifiedAt!).getTime();
        return tb - ta;
      })
      .slice(0, 12)
      .map((t) => ({
        ticketId: t.id,
        title: this.ticketTitle(t),
        at: t.lastNotifiedAt ?? null,
      }));
    this.dash.syncNotifiedFromTickets(rows);
  }

  private applyRunResponse(r: AgentRunResponse): void {
    this.tickets = r.tickets ?? [];
    this.projectSummary = r.projectSummary ?? null;
    this.projectHealth = r.projectHealth ?? null;
    this.generatedAt = r.generatedAt ?? null;
    this.syncNotifiedPreview();
  }

  /** Executive header — overall status emoji. */
  statusEmoji(): string {
    switch (this.projectSummary?.status) {
      case 'RED':
        return '🔴';
      case 'AMBER':
        return '🟡';
      default:
        return '🟢';
    }
  }

  statusLabelShort(): string {
    switch (this.projectSummary?.status) {
      case 'RED':
        return 'RED';
      case 'AMBER':
        return 'AMBER';
      default:
        return 'GREEN';
    }
  }

  portfolioDeliveryRiskDisplay(): string {
    const r = this.projectSummary?.portfolioDeliveryRisk?.toUpperCase();
    if (r === 'HIGH' || r === 'MEDIUM' || r === 'LOW') {
      return r;
    }
    switch (this.projectSummary?.status) {
      case 'RED':
        return 'HIGH';
      case 'AMBER':
        return 'MEDIUM';
      default:
        return 'LOW';
    }
  }

  portfolioDeliveryRiskClass(): string {
    const d = this.portfolioDeliveryRiskDisplay();
    if (d === 'HIGH') {
      return 'exec-risk-high';
    }
    if (d === 'MEDIUM') {
      return 'exec-risk-med';
    }
    return 'exec-risk-low';
  }

  predictedDelayDays(): string {
    const d = this.projectSummary?.estimatedDelayDays;
    if (d == null || d <= 0) {
      return '—';
    }
    return `${d.toFixed(1)} day(s)`;
  }

  lastUpdatedDisplay(): string {
    const raw = this.generatedAt;
    if (!raw) {
      return '—';
    }
    const d = new Date(raw);
    if (Number.isNaN(d.getTime())) {
      return raw;
    }
    return d.toLocaleString();
  }

  kpiTotalTickets(): number {
    return this.projectSummary?.totalTickets ?? this.tickets.length;
  }

  kpiAtRisk(): number {
    return this.projectHealth?.atRiskCount ?? 0;
  }

  kpiBlocked(): number {
    return this.projectHealth?.blockedCount ?? 0;
  }

  /** High or Critical priority, in progress, dwell > 72h. */
  kpiHighPriorityDelayed(): number {
    return this.tickets.filter((t) => {
      const p = (t.priority ?? '').toLowerCase();
      const hi = p === 'high' || p === 'critical';
      const st = (t.displayStatus ?? t.status ?? '').toLowerCase();
      const inProg = st.includes('progress');
      return hi && inProg && t.timeInState > 72;
    }).length;
  }

  kpiAvgTimeInProgressHours(): number | null {
    const inProg = this.tickets.filter((t) => {
      const st = (t.displayStatus ?? t.status ?? '').toLowerCase();
      return st.includes('progress');
    });
    if (!inProg.length) {
      return null;
    }
    const sum = inProg.reduce((a, t) => a + t.timeInState, 0);
    return Math.round((sum / inProg.length) * 10) / 10;
  }

  kpiPrDelayPercent(): number | null {
    const p = this.projectSummary?.prDelayTrendPercent;
    if (p == null || Number.isNaN(p)) {
      return null;
    }
    return Math.round(p);
  }

  kpiPrTrendArrow(): '↑' | '↓' | '→' {
    const p = this.kpiPrDelayPercent();
    if (p == null) {
      return '→';
    }
    if (p > 0) {
      return '↑';
    }
    if (p < 0) {
      return '↓';
    }
    return '→';
  }

  /** Narrative from last two agent-run snapshots. */
  deliveryTrendLines(): string[] {
    const snaps = this.deliveryTrend?.snapshots ?? [];
    if (snaps.length >= 2) {
      const prev = snaps[snaps.length - 2];
      const cur = snaps[snaps.length - 1];
      const lines: string[] = [];
      const prPct = this.pctChange(prev.avgPrHours, cur.avgPrHours);
      const dwellPct = this.pctChange(prev.avgDwellHours, cur.avgDwellHours);
      if (prPct != null) {
        const up = prPct > 0;
        lines.push(
          `PR cycle (batch average) ${up ? 'increased' : prPct < 0 ? 'decreased' : 'held steady'}${prPct !== 0 ? ` by ${Math.abs(Math.round(prPct))}%` : ''} vs previous agent run.`,
        );
      }
      if (dwellPct != null) {
        const up = dwellPct > 0;
        lines.push(
          `Average dwell in status ${up ? 'increased' : dwellPct < 0 ? 'decreased' : 'held steady'}${dwellPct !== 0 ? ` by ${Math.abs(Math.round(dwellPct))}%` : ''} vs previous agent run — ${up ? 'delivery risk is building' : dwellPct < 0 ? 'flow is improving' : 'no material shift'}.`,
        );
      }
      return lines.length ? lines : ['No measurable change between the last two runs.'];
    }
    if (snaps.length === 1 && this.projectSummary?.trendSummary) {
      return [this.projectSummary.trendSummary];
    }
    return [
      'Run the agent at least twice to unlock run-over-run delivery trend (average dwell and PR cycle).',
    ];
  }

  parsePortfolioRead(): { lead: string; bullets: string[] } {
    const raw = this.projectSummary?.projectRiskSummary?.trim();
    if (!raw) {
      return { lead: '', bullets: [] };
    }
    const lines = raw.split('\n').map((l) => l.trim()).filter(Boolean);
    const bullets = lines
      .filter((l) => l.startsWith('-'))
      .map((l) => (l.startsWith('-') ? l.replace(/^-\s*/, '') : l));
    const lead = lines.find((l) => !l.startsWith('-')) ?? '';
    return { lead, bullets };
  }

  portfolioWhyBullets(): string[] {
    const ps = this.projectSummary;
    if (!ps) {
      return [];
    }
    const out: string[] = [];
    if (ps.stuckTickets > 0) {
      out.push(
        `${ps.stuckTickets} ticket${ps.stuckTickets === 1 ? '' : 's'} with dwell past the “stuck” threshold`,
      );
    }
    if (ps.criticalTickets > 0) {
      out.push(`${ps.criticalTickets} in critical dwell or severity bands`);
    }
    const hp = this.kpiHighPriorityDelayed();
    if (hp > 0) {
      out.push(`${hp} high-priority item(s) in progress over 72 hours`);
    }
    const pr = this.kpiPrDelayPercent();
    if (pr != null && pr !== 0 && ps.prDataAvailable !== false) {
      out.push(`PR review / merge cycle vs baseline ${pr > 0 ? 'up' : 'down'} ${Math.abs(pr)}%`);
    }
    if (this.projectHealth && this.projectHealth.blockedCount > 0) {
      out.push(`${this.projectHealth.blockedCount} ticket(s) in blocked / dependency queue`);
    }
    return out;
  }

  portfolioRiskBullets(): string[] {
    const { bullets } = this.parsePortfolioRead();
    if (bullets.length) {
      return bullets;
    }
    const tb = this.humanizeTopBottleneck(this.projectSummary?.topBottleneck);
    return tb ? [`Dominant signal: ${tb}`] : [];
  }

  portfolioActionBullets(): string[] {
    const out: string[] = [];
    const di = this.projectSummary?.deliveryInsight?.trim();
    if (di) {
      out.push(di);
    }
    const ts = this.projectSummary?.trendSummary?.trim();
    if (ts && !out.length) {
      out.push(ts);
    }
    const top = this.humanizeTopBottleneck(this.projectSummary?.topBottleneck);
    if (top?.toLowerCase().includes('review') || this.projectSummary?.topBottleneck === 'PR_DELAY') {
      out.push('Prioritize PR reviews for high-priority and blocked-adjacent items');
    }
    if (top?.toLowerCase().includes('depend') || this.projectSummary?.topBottleneck === 'DEPENDENCY_RISK') {
      out.push('Escalate external dependencies with named owners and dates today');
    }
    if (this.kpiHighPriorityDelayed() > 0) {
      out.push('Pull long-running high-priority work into leadership stand-up until cleared');
    }
    if (!out.length) {
      out.push('Keep weekly rhythm on reviews and dependency check-ins');
    }
    return [...new Set(out)].slice(0, 6);
  }

  whyPortfolioHeading(): string {
    return `Why the portfolio is ${this.statusLabelShort()}`;
  }

  /** Normalize bar height 8–100% for spark strip. */
  trendBarHeight(dwell: number): number {
    const snaps = this.deliveryTrendSnapshots();
    if (!snaps.length) {
      return 20;
    }
    const max = Math.max(...snaps.map((s) => s.avgDwellHours), 1);
    return Math.max(8, Math.round((dwell / max) * 100));
  }

  deliveryTrendSnapshots(): DeliveryTrendPoint[] {
    return this.deliveryTrend?.snapshots ?? [];
  }

  trendBarTitle(s: DeliveryTrendPoint): string {
    return `${s.recordedAt}: ${s.avgDwellHours.toFixed(1)}h avg dwell`;
  }

  humanizeTopBottleneck(code: string | null | undefined): string | null {
    if (!code || code === 'None') {
      return null;
    }
    const m: Record<string, string> = {
      STUCK: 'Excessive time in current status',
      CRITICAL_STUCK: 'Critical dwell time',
      PR_DELAY: 'Pull request review delays',
      DEPENDENCY_RISK: 'External dependency wait',
      BOUNCING: 'Status churn / unclear handoffs',
      BLOCKED: 'Blocked workflow items',
      TREND_SPIKE: 'Dwell above team average',
      SLOWDOWN: 'PR merge slower than peers',
      PR_DATA_MISSING: 'Incomplete PR telemetry',
      DATA_INSUFFICIENT: 'Limited batch data for comparison',
    };
    return m[code] ?? code.replace(/_/g, ' ').toLowerCase();
  }

  priorityGroupLabel(t: Ticket): string {
    const p = (t.priority ?? '').trim();
    if (!p) {
      return '—';
    }
    return p.charAt(0).toUpperCase() + p.slice(1).toLowerCase();
  }

  flagChipMeta(flagCode: string): { icon: string; label: string } {
    const labels: Record<string, string> = {
      STUCK: 'Dwell risk',
      CRITICAL_STUCK: 'Critical dwell',
      PR_DELAY: 'PR review delay',
      DEPENDENCY_RISK: 'Dependency',
      BOUNCING: 'Status churn',
      TREND_SPIKE: 'Dwell vs peers',
      SLOWDOWN: 'PR slowdown',
      BLOCKED: 'Blocked',
      PR_DATA_MISSING: 'PR data gap',
      DATA_INSUFFICIENT: 'Thin data',
    };
    const icons: Record<string, string> = {
      STUCK: '⏱',
      CRITICAL_STUCK: '⚠',
      PR_DELAY: '🔀',
      DEPENDENCY_RISK: '🔗',
      BOUNCING: '↔',
      TREND_SPIKE: '📈',
      SLOWDOWN: '🐢',
      BLOCKED: '🚧',
      PR_DATA_MISSING: '❔',
      DATA_INSUFFICIENT: '📉',
    };
    return {
      icon: icons[flagCode] ?? '🏷',
      label: labels[flagCode] ?? flagCode.replace(/_/g, ' '),
    };
  }

  /** Raw flags on ticket for chips (prefer codes). */
  ticketFlagCodes(t: Ticket): string[] {
    const flags = t.flags ?? [];
    const noise = new Set(['PR_DATA_MISSING', 'DATA_INSUFFICIENT']);
    return flags.filter((f) => !noise.has(f));
  }

  priorityPillClass(t: Ticket): string {
    const p = (t.priority ?? '').toLowerCase();
    if (p === 'critical') {
      return 'pri pri-critical';
    }
    if (p === 'high') {
      return 'pri pri-high';
    }
    if (p === 'medium') {
      return 'pri pri-medium';
    }
    if (p === 'low') {
      return 'pri pri-low';
    }
    return 'pri pri-none';
  }

  firstLine(text: string | null | undefined, maxLen: number): string {
    if (!text?.trim()) {
      return '—';
    }
    const one = text.replace(/\s+/g, ' ').trim();
    const cut = one.length > maxLen ? `${one.slice(0, maxLen - 1)}…` : one;
    return cut;
  }

  rootCauseShort(t: Ticket): string {
    return this.firstLine(t.rootCause, 160);
  }

  actionShort(t: Ticket): string {
    return this.firstLine(t.recommendedAction, 140);
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
        return 'Simulated Data';
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

  /** Tickets after client-side filters. */
  visibleTickets(): Ticket[] {
    return this.tickets.filter((t) => {
      if (this.filterAssignees.length) {
        const a = (t.assignee ?? '').toLowerCase();
        const unassigned = !a || a === 'unassigned';
        const matchUnassigned = this.filterAssignees.includes('__UNASSIGNED__') && unassigned;
        const matchNamed = this.filterAssignees.some((fa) => {
          if (fa === '__UNASSIGNED__') {
            return false;
          }
          return a === fa.toLowerCase();
        });
        if (!matchUnassigned && !matchNamed) {
          return false;
        }
      }
      if (this.filterRisks.length) {
        const r = (t.deliveryRisk ?? '').toUpperCase();
        if (!this.filterRisks.includes(r)) {
          return false;
        }
      }
      if (this.filterStatuses.length) {
        const ds = t.displayStatus?.trim() || t.status;
        if (!this.filterStatuses.includes(ds)) {
          return false;
        }
      }
      if (this.filterPriorities.length) {
        if (!this.filterPriorities.includes(this.normalizePriority(t))) {
          return false;
        }
      }
      if (this.filterBottlenecks.length) {
        const b = (t.bottleneckCategory ?? '').trim() || '—';
        if (!this.filterBottlenecks.includes(b)) {
          return false;
        }
      }
      return true;
    });
  }

  /** Sections for the delivery queue based on {@link #groupByMode}. */
  groupsForDisplay(): { key: string; title: string; tickets: Ticket[] }[] {
    const v = this.visibleTickets();
    const mode = this.effectiveGroupBy();
    if (mode === 'delivery') {
      return this.viewGroupOrder
        .map((key) => ({
          key,
          title: `${this.groupIcon(key)} ${this.groupHeading(key)}`,
          tickets: v.filter((t) => (t.viewGroup ?? '') === key),
        }))
        .filter((g) => g.tickets.length > 0);
    }
    if (mode === 'status') {
      const m = new Map<string, Ticket[]>();
      for (const t of v) {
        const k = this.displayStatus(t);
        if (!m.has(k)) m.set(k, []);
        m.get(k)!.push(t);
      }
      return Array.from(m.entries())
        .sort((a, b) => a[0].localeCompare(b[0]))
        .map(([key, tickets]) => ({ key, title: key, tickets }));
    }
    if (mode === 'assignee') {
      const m = new Map<string, Ticket[]>();
      for (const t of v) {
        const k = this.displayAssignee(t).replace(/\s*⚠️$/, '');
        if (!m.has(k)) m.set(k, []);
        m.get(k)!.push(t);
      }
      return Array.from(m.entries())
        .sort((a, b) => a[0].localeCompare(b[0]))
        .map(([key, tickets]) => ({ key, title: key, tickets }));
    }
    if (mode === 'risk') {
      const m = new Map<string, Ticket[]>();
      for (const t of v) {
        const k = this.riskLabel(t);
        if (!m.has(k)) m.set(k, []);
        m.get(k)!.push(t);
      }
      const order = ['High', 'Medium', 'Low', '—'];
      return Array.from(m.entries())
        .sort((a, b) => order.indexOf(a[0]) - order.indexOf(b[0]))
        .map(([key, tickets]) => ({ key, title: `Risk: ${key}`, tickets }));
    }
    if (mode === 'bottleneck') {
      const m = new Map<string, Ticket[]>();
      for (const t of v) {
        const k = t.bottleneckCategory?.trim() || '—';
        if (!m.has(k)) m.set(k, []);
        m.get(k)!.push(t);
      }
      return Array.from(m.entries())
        .sort((a, b) => a[0].localeCompare(b[0]))
        .map(([key, tickets]) => ({ key, title: key, tickets }));
    }
    if (mode === 'priority') {
      const m = new Map<string, Ticket[]>();
      for (const t of v) {
        const k = this.priorityGroupLabel(t);
        if (!m.has(k)) m.set(k, []);
        m.get(k)!.push(t);
      }
      const order = ['Critical', 'High', 'Medium', 'Low', '—'];
      return Array.from(m.entries())
        .sort((a, b) => order.indexOf(a[0]) - order.indexOf(b[0]))
        .map(([key, tickets]) => ({ key, title: `Priority: ${key}`, tickets }));
    }
    return [];
  }

  private pctChange(prev: number, cur: number): number | null {
    if (prev <= 0) {
      return null;
    }
    return ((cur - prev) / prev) * 100;
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

  uniquePrioritiesForFilter(): string[] {
    const s = new Set<string>();
    for (const t of this.tickets) {
      s.add(this.normalizePriority(t));
    }
    return Array.from(s).sort((a, b) => a.localeCompare(b));
  }

  uniqueBottlenecks(): string[] {
    const s = new Set<string>();
    for (const t of this.tickets) {
      s.add(t.bottleneckCategory?.trim() || '—');
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

  priorityLabel(t: Ticket): string {
    return t.priority?.trim() || '—';
  }

  bottleneckLine(t: Ticket): string {
    return t.bottleneckCategory?.trim() || '—';
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
          DEPENDENCY_RISK: 'Dependency wait risk',
          BOUNCING: 'Status churn',
          TREND_SPIKE: 'Dwell well above team average',
          SLOWDOWN: 'PR merge well above team average',
          BLOCKED: 'Blocked / dependency',
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

  sendToTeams(ticket: Ticket): void {
    const id = ticket.id;
    if (!id || this.notifyingIds[id]) {
      return;
    }
    this.notifyingIds = { ...this.notifyingIds, [id]: true };
    this.api.notifyTicket(id).subscribe({
      next: (r: ManualNotifyResponse) => {
        const rest = { ...this.notifyingIds };
        delete rest[id];
        this.notifyingIds = rest;
        if (r.status === 'skipped') {
          this.showToast('Already notified recently', 'warn');
          return;
        }
        this.patchLastNotified(r.ticketId, r.lastNotifiedAt ?? null);
        const updated = this.tickets.find((x) => x.id === r.ticketId);
        if (updated) {
          this.dash.prependNotified({
            ticketId: updated.id,
            title: this.ticketTitle(updated),
            at: updated.lastNotifiedAt ?? r.lastNotifiedAt ?? null,
          });
        }
        this.showToast('Message sent to Teams', 'ok');
      },
      error: (e: unknown) => {
        const rest = { ...this.notifyingIds };
        delete rest[id];
        this.notifyingIds = rest;
        this.showToast(this.errMessage(e, 'Failed to send to Teams'), 'err');
      },
    });
  }

  isNotifying(id: string): boolean {
    return !!this.notifyingIds[id];
  }

  private patchLastNotified(ticketId: string, at: string | null): void {
    const t = this.tickets.find((x) => x.id === ticketId);
    if (t) {
      t.lastNotifiedAt = at ?? undefined;
    }
  }

  reviewAndSend(s: TicketSuggestion): void {
    const id = s.ticketId;
    const el = document.getElementById(`ticket-card-${id}`);
    if (el) {
      el.scrollIntoView({ behavior: 'smooth', block: 'center' });
    }
    const t = this.tickets.find((x) => x.id === id);
    if (!t) {
      this.showToast('Ticket not in current list — try Refresh', 'err');
      return;
    }
    this.sendToTeams(t);
  }

  private showToast(message: string, kind: 'ok' | 'err' | 'warn'): void {
    if (this.toastTimer) {
      clearTimeout(this.toastTimer);
    }
    this.toast = { message, kind };
    this.toastTimer = setTimeout(() => {
      this.toast = null;
      this.toastTimer = null;
    }, 4000);
  }

  formatLastNotified(iso: string | null | undefined): string {
    if (!iso) {
      return '—';
    }
    const d = new Date(iso);
    if (Number.isNaN(d.getTime())) {
      return iso;
    }
    return d.toLocaleString();
  }
}

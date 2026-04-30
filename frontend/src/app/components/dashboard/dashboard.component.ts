import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HttpErrorResponse } from '@angular/common/http';
import { Component, HostListener, OnInit } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { trigger, transition, style, animate } from '@angular/animations';
import { forkJoin } from 'rxjs';
import { DashboardStateService, SidebarSection } from '../../services/dashboard-state.service';
import { LoadingOverlayComponent } from '../ui/loading-overlay.component';
import {
  AgentRunResponse,
  ApiEnvelope,
  ApiService,
  DeliveryTicketCard,
  ManualNotifyResponse,
  ProjectHealth,
  ProjectSummary,
  Ticket,
  TicketSuggestion,
} from '../../services/api.service';

/** Structured row for the Key insights overview module. */
export interface KeyInsightBlock {
  id: string;
  label: string;
  body: string;
  variant: 'review' | 'dependency' | 'bottleneck' | 'status' | 'neutral';
  metric?: string;
}

@Component({
  selector: 'app-dashboard',
  standalone: true,
  imports: [CommonModule, FormsModule, LoadingOverlayComponent],
  templateUrl: './dashboard.component.html',
  styleUrl: './dashboard.component.css',
  animations: [
    trigger('workspaceSwitch', [
      transition('* => *', [
        style({ opacity: 0, transform: 'translateY(10px)' }),
        animate('280ms cubic-bezier(0.22, 1, 0.36, 1)', style({ opacity: 1, transform: 'translateY(0)' })),
      ]),
    ]),
  ],
})
export class DashboardComponent implements OnInit {
  /** Single-select filter keys; empty string = all. */
  filterAssigneeKey = '';
  filterStatusKey = '';
  filterPriorityKey = '';
  filterRiskKey = '';
  filterBottleneckKey = '';

  tickets: Ticket[] = [];
  suggestions: TicketSuggestion[] = [];
  projectSummary: ProjectSummary | null = null;
  projectHealth: ProjectHealth | null = null;
  deliveryCards: DeliveryTicketCard[] = [];
  generatedAt: string | null = null;
  loading = false;
  /** True until initial insights + suggestions load completes. */
  pageLoading = true;
  error: string | null = null;

  notifyingIds: Record<string, boolean> = {};
  toast: { message: string; kind: 'ok' | 'err' | 'warn' } | null = null;
  private toastTimer: ReturnType<typeof setTimeout> | null = null;

  selectedTicket: Ticket | null = null;
  showExplainability = false;

  filtersExpanded = true;

  readonly skeletonRows = [0, 1, 2, 3, 4];

  constructor(
    private readonly api: ApiService,
    readonly dash: DashboardStateService,
    private readonly route: ActivatedRoute,
  ) {}

  /** Full-screen loader while fetching dashboard data or running the agent. */
  showLoadingOverlay(): boolean {
    return this.pageLoading || this.loading;
  }

  loadingOverlayLabel(): string {
    return this.loading ? 'Running agent…' : 'Loading workspace…';
  }

  ngOnInit(): void {
    this.route.data.subscribe((d) => {
      const section = d['section'] as SidebarSection | undefined;
      if (section) {
        this.dash.setSidebarSection(section);
      }
    });
    this.loadDashboard();
  }

  @HostListener('document:keydown.escape')
  onEscape(): void {
    if (this.selectedTicket) {
      this.closeTicketPanel();
    }
  }

  activeFilterCount(): number {
    let n = 0;
    if (this.filterAssigneeKey) {
      n++;
    }
    if (this.filterStatusKey) {
      n++;
    }
    if (this.filterPriorityKey) {
      n++;
    }
    if (this.filterRiskKey) {
      n++;
    }
    if (this.filterBottleneckKey) {
      n++;
    }
    return n;
  }

  clearFilters(): void {
    this.filterAssigneeKey = '';
    this.filterStatusKey = '';
    this.filterPriorityKey = '';
    this.filterRiskKey = '';
    this.filterBottleneckKey = '';
  }

  trackAssigneeFilterOpt(_i: number, a: { value: string; label: string }): string {
    return a.value;
  }

  trackBottleneckFilterOpt(_i: number, b: { value: string; label: string }): string {
    return b.value;
  }

  loadDashboard(): void {
    this.pageLoading = true;
    this.error = null;
    forkJoin({
      insights: this.api.getInsights(),
      suggestions: this.api.getSuggestions(),
    }).subscribe({
      next: ({ insights, suggestions }) => {
        this.applyRunResponse(insights);
        this.suggestions = suggestions ?? [];
        this.syncPendingSuggestions();
        this.pageLoading = false;
      },
      error: (e: unknown) => {
        this.pageLoading = false;
        this.error = this.errMessage(e, 'Unable to load dashboard');
      },
    });
  }

  stageInsightList(): { stage: string; text: string }[] {
    const si = this.projectSummary?.stageInsights;
    if (!si) {
      return [];
    }
    return Object.entries(si).map(([stage, text]) => ({ stage, text }));
  }

  stageDurationEntries(t: Ticket): { k: string; v: number }[] {
    const sd = t.stageDurations;
    if (!sd) {
      return [];
    }
    return Object.entries(sd).map(([k, v]) => ({ k, v: v ?? 0 }));
  }

  hasExplainabilityDetail(t: Ticket): boolean {
    return (
      (t.explainabilityFactors?.length ?? 0) > 0 ||
      !!(t.stageDurations && Object.keys(t.stageDurations).length > 0)
    );
  }

  toggleExplainability(): void {
    this.showExplainability = !this.showExplainability;
  }

  /** Assignee filter: substring or multi-token partial match (case-insensitive). */
  assigneeFilterMatches(assigneeRaw: string, filterKey: string): boolean {
    const a = (assigneeRaw ?? '').trim().toLowerCase();
    const f = (filterKey ?? '').trim().toLowerCase();
    if (!f) {
      return true;
    }
    if (!a || a === 'unassigned') {
      return false;
    }
    if (a === f || a.includes(f) || f.includes(a)) {
      return true;
    }
    const toks = f.split(/\s+/).filter((t) => t.length >= 2);
    if (toks.length > 1) {
      return toks.every((t) => a.includes(t));
    }
    if (toks.length === 1) {
      return a.includes(toks[0]!);
    }
    return false;
  }

  runAgent(): void {
    this.loading = true;
    this.error = null;
    this.api.runAgent().subscribe({
      next: (r: AgentRunResponse) => {
        this.applyRunResponse(r);
        this.api.getSuggestions().subscribe({
          next: (s) => {
            this.suggestions = s ?? [];
            this.syncPendingSuggestions();
          },
          error: () => (this.suggestions = []),
        });
        this.loading = false;
      },
      error: (e: unknown) => {
        this.loading = false;
        this.error = this.errMessage(e, 'Unable to run agent');
      },
    });
  }

  scopedTickets(): Ticket[] {
    return this.visibleTickets();
  }

  riskViewTickets(): Ticket[] {
    return this.scopedTickets()
      .filter((t) => {
        const r = (t.deliveryRisk ?? '').toUpperCase();
        return r === 'HIGH' || r === 'MEDIUM';
      })
      .sort((a, b) => {
        const ra = (a.deliveryRisk ?? '').toUpperCase() === 'HIGH' ? 2 : 1;
        const rb = (b.deliveryRisk ?? '').toUpperCase() === 'HIGH' ? 2 : 1;
        if (rb !== ra) {
          return rb - ra;
        }
        const ta = a.timeInState ?? 0;
        const tb = b.timeInState ?? 0;
        return tb - ta;
      });
  }

  riskViewGroups(): { key: string; title: string; tickets: Ticket[] }[] {
    const hi = this.riskViewTickets().filter((t) => (t.deliveryRisk ?? '').toUpperCase() === 'HIGH');
    const med = this.riskViewTickets().filter((t) => (t.deliveryRisk ?? '').toUpperCase() === 'MEDIUM');
    const out: { key: string; title: string; tickets: Ticket[] }[] = [];
    if (hi.length) {
      out.push({ key: 'HIGH', title: '🔴 High delivery risk', tickets: hi });
    }
    if (med.length) {
      out.push({ key: 'MEDIUM', title: '⚠️ Medium delivery risk', tickets: med });
    }
    return out;
  }

  readonly bottleneckThemeOrder = [
    'No Development Started',
    'PR Not Created',
    'Review Bottleneck',
    'Deployment Delay',
    'Dependency Block',
    'Execution Delay',
    'No PR / Dev Not Started',
    'Bouncing',
    'Other',
  ] as const;

  canonicalBottleneckBucket(t: Ticket): string | null {
    const raw = (t.bottleneckCategory ?? '').trim();
    const b = raw.toLowerCase();
    if (!raw || raw === '—') {
      return null;
    }
    if (b.includes('progressing normally')) {
      return null;
    }

    const cc = t.commitCount ?? 0;
    const pr = (t.prStatus ?? '').toUpperCase();
    const flags = t.flags ?? [];
    const st = (t.displayStatus ?? t.status ?? '').toLowerCase();
    const inFlight = st.includes('progress') || st.includes('review') || st.includes('block');

    if (cc === 0 && inFlight && !st.includes('backlog') && !st.includes('completed')) {
      return 'No Development Started';
    }
    if (cc > 0 && pr === 'NOT_CREATED') {
      return 'PR Not Created';
    }
    const prAge = t.prAgeHours ?? 0;
    if (pr === 'OPEN' && (prAge > 48 || flags.includes('PR_DELAY'))) {
      return 'Review Bottleneck';
    }
    if (pr === 'MERGED' && t.deployed === false) {
      return 'Deployment Delay';
    }

    const bounce = t.bounceCount ?? t.pingPongTransitions ?? 0;
    if (bounce >= 3 || b.includes('churn')) {
      return 'Bouncing';
    }
    if (b.includes('review slow') || b.includes('pull request')) {
      return 'Review Bottleneck';
    }
    if (b.includes('dependency') || b.includes('another team')) {
      return 'Dependency Block';
    }
    if (b.includes('no pull request') || b.includes('not started') || b.includes('work not started')) {
      return 'No PR / Dev Not Started';
    }
    if (b.includes('too long') || b.includes('waiting in current')) {
      return 'Execution Delay';
    }
    if (pr === 'NOT_CREATED' && st.includes('progress')) {
      return 'No PR / Dev Not Started';
    }
    if (st.includes('review')) {
      return 'Review Bottleneck';
    }
    if (st.includes('block')) {
      return 'Dependency Block';
    }
    if (bounce >= 2) {
      return 'Bouncing';
    }
    if (b.includes('backlog') || b.includes('capacity') || b.includes('throughput')) {
      return 'Execution Delay';
    }
    if (b.includes('unassigned') || b.includes('no owner')) {
      return 'Execution Delay';
    }
    return 'Other';
  }

  bottleneckBlurb(bucket: string): string {
    const m: Record<string, string> = {
      'No Development Started': 'No commits on the linked branch — dev likely not started.',
      'PR Not Created': 'Commits exist but no PR opened for review.',
      'Review Bottleneck':
        'PR open too long, review queue delay, or reviewers waiting on a large diff.',
      'Deployment Delay': 'Merged but not yet promoted to an environment.',
      'Dependency Block': 'External teams, APIs, or approvals blocking flow.',
      'Execution Delay': 'Long dwell in status, capacity, or execution drag.',
      'No PR / Dev Not Started': 'In progress without an opened pull request.',
      Bouncing: 'Repeated QA ↔ dev handoffs and status churn.',
      Other: 'Miscellaneous signals not mapped to a primary theme.',
    };
    return m[bucket] ?? '';
  }

  gitStripVisible(t: Ticket): boolean {
    return !!(t.branchName || t.prStatus != null || (t.commitCount ?? 0) > 0 || t.deployed === true);
  }

  gitPrLabel(t: Ticket): string {
    const p = (t.prStatus ?? '').toUpperCase();
    if (p === 'OPEN') {
      return 'OPEN';
    }
    if (p === 'MERGED') {
      return 'MERGED';
    }
    if (p === 'NOT_CREATED') {
      return 'NONE';
    }
    return p || '—';
  }

  gitDeployLine(t: Ticket): string {
    if (t.deployed) {
      const env = (t.deployEnvironment ?? 'ENV').toUpperCase();
      const tag = t.deploymentTag?.trim();
      return tag ? `${env} · ${tag}` : env;
    }
    if ((t.prStatus ?? '').toUpperCase() === 'MERGED' && t.deployed === false) {
      return 'Not yet (merged)';
    }
    return 'Not yet';
  }

  bottleneckDistribution(): { bucket: string; count: number; pct: number }[] {
    const scoped = this.scopedTickets();
    const analyzed = scoped.filter((t) => this.canonicalBottleneckBucket(t) != null);
    const m = new Map<string, number>();
    for (const t of analyzed) {
      const k = this.canonicalBottleneckBucket(t)!;
      m.set(k, (m.get(k) ?? 0) + 1);
    }
    const total = analyzed.length || 1;
    return this.bottleneckThemeOrder
      .map((bucket) => {
        const count = m.get(bucket) ?? 0;
        return { bucket, count, pct: Math.round((count / total) * 1000) / 10 };
      })
      .filter((x) => x.count > 0);
  }

  bottleneckViewGroups(): { bucket: string; tickets: Ticket[]; pct: number }[] {
    const scoped = this.scopedTickets();
    const analyzed = scoped.filter((t) => this.canonicalBottleneckBucket(t) != null);
    const total = analyzed.length || 1;
    const m = new Map<string, Ticket[]>();
    for (const t of analyzed) {
      const k = this.canonicalBottleneckBucket(t)!;
      if (!m.has(k)) {
        m.set(k, []);
      }
      m.get(k)!.push(t);
    }
    return this.bottleneckThemeOrder
      .map((bucket) => {
        const tickets = m.get(bucket) ?? [];
        return {
          bucket,
          tickets,
          pct: Math.round((tickets.length / total) * 1000) / 10,
        };
      })
      .filter((g) => g.tickets.length > 0);
  }

  ticketCompactTime(t: Ticket): string {
    return t.timeInStatusLabel?.trim() || `${t.timeInState}h`;
  }

  ticketRiskReason(t: Ticket): string {
    return (
      this.firstLine(t.insight ?? t.reasoning ?? t.flagSummary ?? t.nudge ?? t.rootCause, 200) || 'Delivery risk elevated for this item.'
    );
  }

  overviewTotalTickets(): number {
    return this.portfolioTickets().length;
  }

  overviewAtRiskCount(): number {
    return this.portfolioTickets().filter(
      (t) =>
        (t.viewGroup ?? '') === 'AT_RISK' ||
        (t.deliveryRisk ?? '').toUpperCase() === 'HIGH' ||
        (t.deliveryRisk ?? '').toUpperCase() === 'MEDIUM',
    ).length;
  }

  overviewBlockedCount(): number {
    return this.portfolioTickets().filter(
      (t) => (t.viewGroup ?? '') === 'BLOCKED' || this.displayStatus(t).toLowerCase().includes('block'),
    ).length;
  }

  overviewHighPriorityDelayed(): number {
    return this.portfolioTickets().filter((t) => {
      const p = (t.priority ?? '').toLowerCase();
      const hi = p === 'high' || p === 'critical';
      const st = (t.displayStatus ?? t.status ?? '').toLowerCase();
      const inProg = st.includes('progress');
      return hi && inProg && t.timeInState > 72;
    }).length;
  }

  overviewAvgTimeInProgressHours(): number | null {
    const inProg = this.portfolioTickets().filter((t) => {
      const st = (t.displayStatus ?? t.status ?? '').toLowerCase();
      return st.includes('progress');
    });
    if (!inProg.length) {
      return null;
    }
    const sum = inProg.reduce((a, t) => a + t.timeInState, 0);
    return Math.round((sum / inProg.length) * 10) / 10;
  }

  statusPieSlices(): { label: string; count: number; color: string; pct: number }[] {
    const palette = ['#6366f1', '#8b5cf6', '#ec4899', '#f97316', '#14b8a6', '#0ea5e9', '#64748b'];
    const counts = new Map<string, number>();
    for (const t of this.scopedTickets()) {
      const k = this.displayStatus(t);
      counts.set(k, (counts.get(k) ?? 0) + 1);
    }
    const entries = [...counts.entries()].sort((a, b) => b[1] - a[1]);
    const total = this.scopedTickets().length || 1;
    return entries.map(([label, count], i) => ({
      label,
      count,
      color: palette[i % palette.length]!,
      pct: (count / total) * 100,
    }));
  }

  statusPieGradient(): string {
    const slices = this.statusPieSlices();
    if (!slices.length) {
      return 'conic-gradient(#64748b 0% 100%)';
    }
    let acc = 0;
    const parts = slices.map((s) => {
      const start = acc;
      acc += s.pct;
      return `${s.color} ${start}% ${acc}%`;
    });
    return `conic-gradient(${parts.join(', ')})`;
  }

  priorityBarRows(): { label: string; count: number; pct: number; barClass: string }[] {
    const order = ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW', '—'];
    const counts = new Map<string, number>();
    for (const t of this.scopedTickets()) {
      const k = this.normalizePriority(t);
      counts.set(k, (counts.get(k) ?? 0) + 1);
    }
    const total = this.scopedTickets().length || 1;
    return order
      .filter((k) => (counts.get(k) ?? 0) > 0)
      .map((label) => {
        const count = counts.get(label) ?? 0;
        const pct = (count / total) * 100;
        let barClass = 'bar-pri-mid';
        if (label === 'CRITICAL' || label === 'HIGH') {
          barClass = 'bar-pri-hi';
        }
        if (label === 'LOW' || label === '—') {
          barClass = 'bar-pri-low';
        }
        return { label, count, pct, barClass };
      });
  }

  riskTrendPoints(): { label: string; y: number }[] {
    const tk = this.scopedTickets();
    const stress = Math.min(
      100,
      tk.filter((t) => (t.deliveryRisk ?? '').toUpperCase() === 'HIGH').length * 9 +
        tk.filter((t) => (t.deliveryRisk ?? '').toUpperCase() === 'MEDIUM').length * 4 +
        tk.filter((t) => (t.viewGroup ?? '') === 'BLOCKED').length * 5 +
        12,
    );
    return [
      { label: 't−2', y: Math.max(8, stress - 18) },
      { label: 't−1', y: Math.max(6, stress - 7) },
      { label: 'Now', y: stress },
    ];
  }

  riskTrendPolylinePoints(): string {
    const pts = this.riskTrendPoints();
    if (!pts.length) {
      return '';
    }
    const n = pts.length;
    return pts
      .map((p, i) => {
        const x = n === 1 ? 50 : (i / (n - 1)) * 100;
        const y = 100 - p.y * 0.85 - 8;
        return `${x},${y}`;
      })
      .join(' ');
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
    this.showExplainability = false;
  }

  closeTicketPanel(): void {
    this.selectedTicket = null;
    this.showExplainability = false;
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
      DEV_NOT_STARTED: 'NO COMMITS',
      PR_NOT_CREATED: 'NO PR',
      MERGED_NOT_DEPLOYED: 'NOT DEPLOYED',
      ACTIVE_DEVELOPMENT: 'ACTIVE DEV',
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
    this.deliveryCards = r.deliveryCards ?? [];
    this.generatedAt = r.generatedAt ?? null;
    this.syncNotifiedPreview();
    this.syncPendingSuggestions();
  }

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

  deliveryBadgeClass(status: string | null | undefined): string {
    const s = (status ?? '').toUpperCase();
    if (s.includes('DELAY')) {
      return 'd-badge d-badge--delayed';
    }
    if (s.includes('RISK')) {
      return 'd-badge d-badge--risk';
    }
    return 'd-badge d-badge--ontrack';
  }

  deliveryBadgeLabel(status: string | null | undefined): string {
    const s = (status ?? '').toUpperCase();
    if (s.includes('DELAY')) {
      return 'Delayed';
    }
    if (s.includes('RISK')) {
      return 'At risk';
    }
    return 'On track';
  }

  /** Delivery tab: open / in-flight tickets first, then completed. */
  deliveryCardsView(): DeliveryTicketCard[] {
    const rows = this.deliveryCards ?? [];
    return [...rows].sort((a, b) => {
      const aDone = a.estimatedCompletion === 'Complete' ? 1 : 0;
      const bDone = b.estimatedCompletion === 'Complete' ? 1 : 0;
      if (aDone !== bDone) {
        return aDone - bDone;
      }
      return (a.ticketId ?? '').localeCompare(b.ticketId ?? '');
    });
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

  kpiHighPriorityDelayed(): number {
    return this.scopedTickets().filter((t) => {
      const p = (t.priority ?? '').toLowerCase();
      const hi = p === 'high' || p === 'critical';
      const st = (t.displayStatus ?? t.status ?? '').toLowerCase();
      const inProg = st.includes('progress');
      return hi && inProg && t.timeInState > 72;
    }).length;
  }

  kpiAvgTimeInProgressHours(): number | null {
    const inProg = this.scopedTickets().filter((t) => {
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
      DEV_NOT_STARTED: 'No development commits yet',
      PR_NOT_CREATED: 'Work without pull request',
      MERGED_NOT_DEPLOYED: 'Merged code awaiting deployment',
      ACTIVE_DEVELOPMENT: 'Recent commits on branch',
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
      DEV_NOT_STARTED: 'No commits',
      PR_NOT_CREATED: 'PR not opened',
      MERGED_NOT_DEPLOYED: 'Merged, not deployed',
      ACTIVE_DEVELOPMENT: 'Recent commits',
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
      DEV_NOT_STARTED: '🧾',
      PR_NOT_CREATED: '🔀',
      MERGED_NOT_DEPLOYED: '🚀',
      ACTIVE_DEVELOPMENT: '💻',
    };
    return {
      icon: icons[flagCode] ?? '🏷',
      label: labels[flagCode] ?? flagCode.replace(/_/g, ' '),
    };
  }

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
      if (e.error && typeof e.error === 'object') {
        const env = e.error as ApiEnvelope<unknown>;
        if (env.error?.message) {
          return env.error.message;
        }
        const msg = (e.error as { message?: string }).message;
        if (typeof msg === 'string' && msg.length > 0) {
          return msg;
        }
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
    return null;
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

  visibleTickets(): Ticket[] {
    return this.tickets.filter((t) => {
      if (this.filterAssigneeKey) {
        const raw = (t.assignee ?? '').trim();
        const unassigned = !raw || raw.toLowerCase() === 'unassigned';
        if (this.filterAssigneeKey === '__UNASSIGNED__') {
          if (!unassigned) {
            return false;
          }
        } else if (!this.assigneeFilterMatches(raw, this.filterAssigneeKey)) {
          return false;
        }
      }
      if (this.filterStatusKey) {
        if (this.filterDisplayStatus(t) !== this.filterStatusKey) {
          return false;
        }
      }
      if (this.filterPriorityKey) {
        if (this.normalizePriority(t) !== this.filterPriorityKey) {
          return false;
        }
      }
      if (this.filterRiskKey) {
        const r = (t.deliveryRisk ?? 'LOW').toUpperCase();
        if (r !== this.filterRiskKey.toUpperCase()) {
          return false;
        }
      }
      if (this.filterBottleneckKey) {
        const b = this.canonicalBottleneckBucket(t);
        const key = b ?? '__NONE__';
        if (key !== this.filterBottleneckKey) {
          return false;
        }
      }
      return true;
    });
  }

  uniqueRiskLevels(): string[] {
    const s = new Set<string>();
    for (const t of this.tickets) {
      s.add((t.deliveryRisk ?? 'LOW').toUpperCase());
    }
    return Array.from(s).sort();
  }

  uniqueBottleneckBucketsForFilter(): { value: string; label: string }[] {
    const s = new Set<string>();
    let anyUnclassified = false;
    for (const t of this.tickets) {
      const b = this.canonicalBottleneckBucket(t);
      if (b) {
        s.add(b);
      } else {
        anyUnclassified = true;
      }
    }
    const rows = Array.from(s)
      .sort((a, b) => a.localeCompare(b))
      .map((v) => ({ value: v, label: v }));
    if (anyUnclassified) {
      rows.push({ value: '__NONE__', label: 'Not classified' });
    }
    return rows;
  }

  portfolioTickets(): Ticket[] {
    return this.tickets;
  }

  healthDistributionSlices(): { label: string; count: number; color: string; pct: number }[] {
    const tk = this.visibleTickets();
    let blocked = 0;
    let atRisk = 0;
    let healthy = 0;
    for (const t of tk) {
      if ((t.status ?? '').toLowerCase() === 'done') {
        continue;
      }
      const st = (t.displayStatus ?? t.status ?? '').toLowerCase();
      if ((t.viewGroup ?? '') === 'BLOCKED' || st.includes('block')) {
        blocked++;
        continue;
      }
      const r = (t.deliveryRisk ?? '').toUpperCase();
      if (r === 'HIGH' || r === 'MEDIUM') {
        atRisk++;
      } else {
        healthy++;
      }
    }
    const total = blocked + atRisk + healthy || 1;
    return [
      {
        label: 'Healthy',
        count: healthy,
        color: 'var(--state-healthy)',
        pct: (healthy / total) * 100,
      },
      {
        label: 'At risk',
        count: atRisk,
        color: 'var(--state-at-risk)',
        pct: (atRisk / total) * 100,
      },
      {
        label: 'Blocked',
        count: blocked,
        color: 'var(--state-blocked)',
        pct: (blocked / total) * 100,
      },
    ];
  }

  healthDonutGradient(): string {
    const slices = this.healthDistributionSlices();
    if (!slices.some((s) => s.count > 0)) {
      return 'conic-gradient(var(--muted) 0% 100%)';
    }
    let acc = 0;
    const parts = slices.map((s) => {
      if (s.count === 0) {
        return null;
      }
      const start = acc;
      acc += s.pct;
      return `${s.color} ${start}% ${acc}%`;
    }).filter((x): x is string => x != null);
    return `conic-gradient(${parts.join(', ')})`;
  }

  bottleneckSummaryCards(): { title: string; count: number; impact: string; fix: string; variant: string }[] {
    const dist = this.bottleneckDistributionUnscoped();
    const fixes: Record<string, string> = {
      'Review Bottleneck': 'Add a second reviewer for LOS changes and time-box reviews.',
      'Dependency Block': 'Escalate the bank API owner with a joint checkpoint this week.',
      'No PR / Dev Not Started': 'Confirm scope with product and open implementation for the oldest items.',
      'Bouncing': 'Pair QA and engineering on acceptance criteria before the next build.',
      'Execution Delay': 'Reprioritize backlog and pull one stuck loan-critical item per engineer.',
      'PR Not Created': 'Open a change request for branches that already have commits.',
      'No Development Started': 'Kick off implementation or reassign from overloaded peers.',
      'Deployment Delay': 'Align release management on the next LMS deployment window.',
      Other: 'Triage in stand-up and assign a single accountable owner.',
    };
    const impacts: Record<string, string> = {
      'Review Bottleneck': 'Slows loan payouts and compliance deliverables.',
      'Dependency Block': 'Customer-visible delays when banks do not confirm.',
      'No PR / Dev Not Started': 'Engineering throughput looks idle while tickets age.',
      Bouncing: 'Extra cycles before EMI and statement fixes reach borrowers.',
      'Execution Delay': 'Backlog congestion hides capacity for urgent loans.',
      'PR Not Created': 'Changes stay invisible to reviewers and audit trails.',
      'No Development Started': 'Risk compounds while regulatory dates approach.',
      'Deployment Delay': 'Fixes stay out of production environments.',
      Other: 'Mixed causes — harder to forecast disbursement dates.',
    };
    return dist.slice(0, 3).map((d) => ({
      title: d.bucket,
      count: d.count,
      impact: impacts[d.bucket] ?? impacts['Other'],
      fix: fixes[d.bucket] ?? fixes['Other'],
      variant: this.bottleneckSummaryVariant(d.bucket),
    }));
  }

  /** CSS theme token for bottleneck summary cards (accent bar + tint). */
  private bottleneckSummaryVariant(bucket: string): string {
    const b = bucket.toLowerCase();
    if (b.includes('review')) {
      return 'review';
    }
    if (b.includes('deployment')) {
      return 'deploy';
    }
    if (b.includes('execution')) {
      return 'exec';
    }
    if (b.includes('dependency')) {
      return 'dep';
    }
    if (b.includes('bouncing')) {
      return 'bounce';
    }
    if (b.includes('pr not') || b.includes('no pr')) {
      return 'pr';
    }
    if (b.includes('development') || b.includes('not started')) {
      return 'dev';
    }
    return 'other';
  }

  trackInsight(_index: number, block: KeyInsightBlock): string {
    return block.id;
  }

  /**
   * Structured insights for the overview: labeled cards, optional metrics, neutral enterprise tone.
   */
  keyInsightBlocks(): KeyInsightBlock[] {
    const blocks: KeyInsightBlock[] = [];
    const visible = this.visibleTickets();

    const hp = visible.filter(
      (t) =>
        (t.priority ?? '').toLowerCase().includes('high') &&
        (t.status ?? '').toLowerCase().includes('review'),
    ).length;
    if (hp > 0) {
      blocks.push({
        id: 'review-capacity',
        label: 'Review throughput',
        body: `${hp} high-priority loan item(s) remain in review. Dwell in this stage points to reviewer capacity or batch size rather than missing acceptance criteria.`,
        variant: 'review',
        metric: `${hp} in review`,
      });
    }

    const dep = visible.filter(
      (t) =>
        (t.dependency ?? '').toUpperCase() !== 'NONE' &&
        (t.status ?? '').toLowerCase() !== 'done',
    ).length;
    if (dep >= 4) {
      blocks.push({
        id: 'dependencies',
        label: 'External dependencies',
        body: 'Several active tickets reference partner banks or APIs. Risk is concentrated in coordination, approvals, and environment readiness—not in raw implementation velocity.',
        variant: 'dependency',
        metric: `${dep} linked`,
      });
    }

    const bn = this.bottleneckSummaryCards();
    if (bn.length > 0) {
      const top = bn[0]!;
      blocks.push({
        id: 'bottleneck-theme',
        label: 'Dominant bottleneck theme',
        body: `Within the current scope, ${top.title} is the most frequent classified delay driver.`,
        variant: 'bottleneck',
        metric: `${top.count} tickets`,
      });
    }

    if (!blocks.length && this.projectSummary?.reasonForStatus) {
      blocks.push({
        id: 'status-aligned',
        label: 'Portfolio status',
        body: this.projectSummary.reasonForStatus,
        variant: 'status',
      });
    }
    if (!blocks.length) {
      blocks.push({
        id: 'stable',
        label: 'Delivery posture',
        body: 'Signals are within normal variance for this portfolio. Continue scheduled dependency reviews on sponsor-bank interfaces and keep review WIP visible in stand-ups.',
        variant: 'neutral',
      });
    }
    return blocks.slice(0, 5);
  }

  /** When the status rationale is already the sole insight card, omit the duplicate narrative block. */
  showPortfolioNarrative(): boolean {
    const rs = this.projectSummary?.reasonForStatus?.trim();
    if (!rs) {
      return false;
    }
    const blocks = this.keyInsightBlocks();
    if (blocks.length === 1 && blocks[0].variant === 'status') {
      return false;
    }
    return true;
  }

  ticketsForListView(): Ticket[] {
    return [...this.scopedTickets()].sort((a, b) => {
      const pa = this.priorityOrder(a);
      const pb = this.priorityOrder(b);
      if (pb !== pa) {
        return pb - pa;
      }
      return (b.timeInState ?? 0) - (a.timeInState ?? 0);
    });
  }

  private priorityOrder(t: Ticket): number {
    const p = (t.priority ?? '').toLowerCase();
    if (p === 'critical') {
      return 4;
    }
    if (p === 'high') {
      return 3;
    }
    if (p === 'medium') {
      return 2;
    }
    if (p === 'low') {
      return 1;
    }
    return 0;
  }

  private bottleneckDistributionUnscoped(): { bucket: string; count: number; pct: number }[] {
    const scoped = this.visibleTickets();
    const analyzed = scoped.filter((t) => this.canonicalBottleneckBucket(t) != null);
    const m = new Map<string, number>();
    for (const t of analyzed) {
      const k = this.canonicalBottleneckBucket(t)!;
      m.set(k, (m.get(k) ?? 0) + 1);
    }
    const total = analyzed.length || 1;
    return this.bottleneckThemeOrder
      .map((bucket) => {
        const count = m.get(bucket) ?? 0;
        return { bucket, count, pct: Math.round((count / total) * 1000) / 10 };
      })
      .filter((x) => x.count > 0)
      .sort((a, b) => b.count - a.count);
  }

  private syncPendingSuggestions(): void {
    this.dash.syncPendingFromSuggestions(
      this.suggestions.slice(0, 8).map((s) => ({
        ticketId: s.ticketId,
        title:
          this.tickets.find((x) => x.id === s.ticketId)?.summary?.trim() || s.ticketId,
        action: s.recommendedAction,
      })),
    );
  }

  uniqueAssignees(): { value: string; label: string }[] {
    const byKey = new Map<string, string>();
    for (const t of this.tickets) {
      const raw = (t.assignee ?? '').trim();
      const rl = raw.toLowerCase();
      if (!raw || rl === 'unassigned') {
        byKey.set('__UNASSIGNED__', 'Unassigned ⚠️');
      } else {
        const key = raw.toLowerCase();
        if (!byKey.has(key)) {
          byKey.set(key, raw);
        }
      }
    }
    const keys = Array.from(byKey.keys()).sort((a, b) => {
      if (a === '__UNASSIGNED__') {
        return -1;
      }
      if (b === '__UNASSIGNED__') {
        return 1;
      }
      return (byKey.get(a) ?? '').localeCompare(byKey.get(b) ?? '');
    });
    return keys.map((k) => ({ value: k, label: byKey.get(k)! }));
  }

  /** Right column on Overview — same ticket scope as risk donut (respects filters). */
  overviewScopeSummary(): {
    heading: string;
    sub: string;
    rows: { k: string; v: string }[];
  } {
    const tk = this.visibleTickets();
    const total = tk.length;
    const done = tk.filter((t) => (t.status ?? '').toLowerCase() === 'done').length;
    const active = total - done;
    const hiPri = tk.filter((t) => {
      const p = (t.priority ?? '').toLowerCase();
      return p === 'high' || p === 'critical';
    }).length;
    const blocked = tk.filter((t) => {
      const st = (t.displayStatus ?? t.status ?? '').toLowerCase();
      return (t.viewGroup ?? '') === 'BLOCKED' || st.includes('block');
    }).length;
    const filtersOn = this.activeFilterCount() > 0;
    return {
      heading: filtersOn ? 'Scoped snapshot' : 'Active snapshot',
      sub: filtersOn
        ? `${active} active in view (${total} tickets match filters, ${done} done in view).`
        : `${active} active tickets in portfolio (${done} done). Use Tickets filters to narrow this chart.`,
      rows: [
        { k: 'Tickets in scope', v: String(total) },
        { k: 'High / critical', v: String(hiPri) },
        { k: 'Blocked', v: String(blocked) },
      ],
    };
  }

  uniqueDisplayStatuses(): string[] {
    const s = new Set<string>();
    for (const t of this.tickets) {
      s.add(this.filterDisplayStatus(t));
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

  displayStatus(t: Ticket): string {
    return (t.displayStatus?.trim() || t.status) || '—';
  }

  filterDisplayStatus(t: Ticket): string {
    const raw = t.displayStatus?.trim() || t.status?.trim();
    return raw && raw.length ? raw : '—';
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
      DEV_NOT_STARTED: 'No Git commits yet',
      PR_NOT_CREATED: 'PR not opened',
      MERGED_NOT_DEPLOYED: 'Merged, awaiting deploy',
      ACTIVE_DEVELOPMENT: 'Recent commit activity',
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

  copyTicketAction(ticket: Ticket): void {
    const action = (ticket.recommendedAction ?? '').trim();
    const owner = (ticket.actionOwner ?? '').trim();
    const lines = [action, owner ? `Owner focus: ${owner}` : ''].filter(Boolean).join('\n');
    if (!lines) {
      return;
    }
    if (typeof navigator !== 'undefined' && navigator.clipboard?.writeText) {
      void navigator.clipboard.writeText(lines);
    }
    this.showToast('Recommended action copied to clipboard', 'ok');
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
      this.showToast('That ticket is not in the current view.', 'err');
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

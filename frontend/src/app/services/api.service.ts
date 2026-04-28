import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';

export type ProjectStatus = 'RED' | 'AMBER' | 'GREEN';

export type DataQuality = 'HIGH' | 'PARTIAL' | 'MOCK';

export interface ProjectSummary {
  totalTickets: number;
  stuckTickets: number;
  criticalTickets: number;
  topBottleneck: string;
  status: ProjectStatus;
  /** HIGH | MEDIUM | LOW — aligned with RED / AMBER / GREEN. */
  portfolioDeliveryRisk?: string | null;
  prDelayTrendPercent: number | null;
  estimatedDelayDays: number | null;
  /** Run-over-run or baseline narrative */
  trendSummary?: string | null;
  /** Plain-language explanation for RED/AMBER/GREEN */
  reasonForStatus?: string | null;
  /** Executive narrative with bullet-style lines (newlines). */
  projectRiskSummary?: string | null;
  /** Lightweight delivery forecast when many tickets show long dwell. */
  deliveryInsight?: string | null;
  dataQuality?: DataQuality | null;
  prDataAvailable?: boolean;
  jiraDataAvailable?: boolean;
}

/** Counts for the “project health” summary strip. */
export interface ProjectHealth {
  totalOpenTickets: number;
  highRiskCount: number;
  blockedCount: number;
  atRiskCount: number;
  healthyCount: number;
  unassignedCount: number;
}

export interface Ticket {
  id: string;
  /** Issue title */
  summary?: string | null;
  status: string;
  statusCategory?: string | null;
  displayStatus?: string | null;
  createdAt?: string | null;
  jiraUpdatedAt?: string | null;
  /** Friendly timing for UI */
  progressLabel?: string | null;
  flagSummary?: string | null;
  assignee?: string | null;
  priority?: string | null;
  bottleneckCategory?: string | null;
  /** Capped hours in current status (backend) */
  timeInState: number;
  prTime: number;
  /** NOT_CREATED | OPEN | IN_REVIEW | MERGED (simulation) */
  prStatus?: string | null;
  /** NONE | API | DESIGN | EXTERNAL_TEAM (simulation) */
  dependency?: string | null;
  /** Dev ↔ QA bounce count (simulation) */
  bounceCount?: number;
  /** SIMPLE | MEDIUM | COMPLEX (simulation) */
  complexity?: string | null;
  /** Synthetic PR number (demo GitHub placeholder) */
  prNumber?: number | null;
  /** e.g. https://github.com/demo-org/aipmo-platform/pull/142 */
  prUrl?: string | null;
  branchName?: string | null;
  lastCommitAt?: string | null;
  prAuthor?: string | null;
  statusChanges?: number;
  pingPongTransitions?: number;
  flags: string[];
  insight?: string | null;
  nudge?: string | null;
  reasoning?: string | null;
  rootCause?: string | null;
  impact?: string | null;
  recommendedAction?: string | null;
  severity?: string | null;
  trendIndicator?: string | null;
  confidence?: string | null;
  lastUpdated?: string | null;
  dataQuality?: DataQuality | null;
  jiraDataAvailable?: boolean;
  prDataAvailable?: boolean;
  /** Normal | Watch | At Risk */
  agingBucket?: string | null;
  /** User-facing risk from backend */
  deliveryRisk?: string | null;
  /** Active | No Activity */
  movementStatus?: string | null;
  /** e.g. "6 days" */
  timeInStatusLabel?: string | null;
  /** e.g. "2 days ago" */
  lastActivityLabel?: string | null;
  /** UNASSIGNED | BLOCKED | AT_RISK | HEALTHY */
  viewGroup?: string | null;
  /** ISO timestamp after manual Teams notify */
  lastNotifiedAt?: string | null;
}

export interface ManualNotifyResponse {
  status: string;
  ticketId: string;
  assignee: string;
  messagePreview: string | null;
  lastNotifiedAt?: string | null;
  /** Set when status is skipped (e.g. notify cooldown). */
  reason?: string | null;
}

export interface TicketSuggestion {
  ticketId: string;
  reason: string;
  recommendedAction: string;
}

export interface AgentRunResponse {
  tickets: Ticket[];
  projectSummary: ProjectSummary | null;
  projectHealth: ProjectHealth | null;
  /** Always true — synthetic dataset only. */
  simulation?: boolean;
  /** ISO-8601 when this snapshot was built. */
  generatedAt?: string | null;
}

export interface DeliveryTrendPoint {
  recordedAt: string;
  avgPrHours: number;
  avgDwellHours: number;
  ticketCount: number;
}

export interface DeliveryTrend {
  snapshots: DeliveryTrendPoint[];
}

@Injectable({
  providedIn: 'root',
})
export class ApiService {
  private readonly base = '';

  constructor(private readonly http: HttpClient) {}

  getTickets(): Observable<Ticket[]> {
    return this.http.get<Ticket[]>(`${this.base}/api/tickets`);
  }

  runAgent(): Observable<AgentRunResponse> {
    return this.http.post<AgentRunResponse>(`${this.base}/api/run-agent`, {});
  }

  /** Baseline or last agent snapshot (includes project summary). */
  getInsights(): Observable<AgentRunResponse> {
    return this.http.get<AgentRunResponse>(`${this.base}/api/insights`);
  }

  /** Proactive hints from metrics + severity (no Teams send). */
  getSuggestions(): Observable<TicketSuggestion[]> {
    return this.http.get<TicketSuggestion[]>(`${this.base}/api/suggestions`);
  }

  notifyTicket(ticketId: string): Observable<ManualNotifyResponse> {
    const enc = encodeURIComponent(ticketId);
    return this.http.post<ManualNotifyResponse>(`${this.base}/api/notify/${enc}`, {});
  }

  /** Run-over-run dwell/PR averages (after repeated Run Agent). */
  getDeliveryTrend(): Observable<DeliveryTrend> {
    return this.http.get<DeliveryTrend>(`${this.base}/api/delivery-trend`);
  }
}

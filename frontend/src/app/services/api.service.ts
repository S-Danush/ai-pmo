import { HttpClient, HttpParams } from '@angular/common/http';
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
  prDelayTrendPercent: number | null;
  estimatedDelayDays: number | null;
  /** Run-over-run or baseline narrative */
  trendSummary?: string | null;
  /** Plain-language explanation for RED/AMBER/GREEN */
  reasonForStatus?: string | null;
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
  /** Jira / demo issue title */
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
  /** Capped hours in current status (backend) */
  timeInState: number;
  prTime: number;
  statusChanges?: number;
  pingPongTransitions?: number;
  flags: string[];
  insight?: string | null;
  nudge?: string | null;
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
}

export interface AgentRunResponse {
  tickets: Ticket[];
  projectSummary: ProjectSummary | null;
  projectHealth: ProjectHealth | null;
  /** True when this snapshot came from simulation mode (SIM-* dataset). */
  simulation?: boolean;
}

@Injectable({
  providedIn: 'root',
})
export class ApiService {
  private readonly base = '';

  constructor(private readonly http: HttpClient) {}

  getTickets(simulation: boolean): Observable<Ticket[]> {
    return this.http.get<Ticket[]>(`${this.base}/api/tickets`, {
      params: this.simParams(simulation),
    });
  }

  runAgent(simulation: boolean): Observable<AgentRunResponse> {
    return this.http.post<AgentRunResponse>(
      `${this.base}/api/run-agent`,
      {},
      { params: this.simParams(simulation) },
    );
  }

  /** Baseline or last agent snapshot (includes project summary). */
  getInsights(simulation: boolean): Observable<AgentRunResponse> {
    return this.http.get<AgentRunResponse>(`${this.base}/api/insights`, {
      params: this.simParams(simulation),
    });
  }

  private simParams(simulation: boolean): HttpParams {
    return simulation ? new HttpParams().set('simulation', 'true') : new HttpParams();
  }
}

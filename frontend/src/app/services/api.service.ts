import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { map } from 'rxjs/operators';

export type ProjectStatus = 'RED' | 'AMBER' | 'GREEN';

export type DataQuality = 'HIGH' | 'PARTIAL' | 'MOCK';

/** Standard API envelope from the backend. */
export interface ApiEnvelope<T> {
  success: boolean;
  data: T | null;
  error: { message: string; status: number } | null;
}

export interface ProjectSummary {
  totalTickets: number;
  stuckTickets: number;
  criticalTickets: number;
  topBottleneck: string;
  status: ProjectStatus;
  portfolioDeliveryRisk?: string | null;
  prDelayTrendPercent: number | null;
  estimatedDelayDays: number | null;
  trendSummary?: string | null;
  reasonForStatus?: string | null;
  projectRiskSummary?: string | null;
  deliveryInsight?: string | null;
  avgStageTat?: Record<string, number> | null;
  stageInsights?: Record<string, string> | null;
  predictedGoLiveDate?: string | null;
  /** AI / derived velocity phrase */
  deliveryVelocity?: string | null;
  /** Bottleneck SDLC stage */
  slowestStage?: string | null;
  deliveryConfidence?: string | null;
  predictionReason?: string | null;
  dataQuality?: DataQuality | null;
  prDataAvailable?: boolean;
  jiraDataAvailable?: boolean;
}

export interface ProjectHealth {
  totalOpenTickets: number;
  highRiskCount: number;
  blockedCount: number;
  atRiskCount: number;
  healthyCount: number;
  unassignedCount: number;
}

export type SimulationScenarioTier = 'GREEN' | 'AMBER' | 'RED';

export interface RootCauseAnalysis {
  reasons: string[];
  primaryCause: string;
  confidence: string;
}

export interface Ticket {
  id: string;
  summary?: string | null;
  status: string;
  statusCategory?: string | null;
  displayStatus?: string | null;
  createdAt?: string | null;
  jiraUpdatedAt?: string | null;
  progressLabel?: string | null;
  flagSummary?: string | null;
  assignee?: string | null;
  priority?: string | null;
  bottleneckCategory?: string | null;
  timeInState: number;
  prTime: number;
  prStatus?: string | null;
  dependency?: string | null;
  bounceCount?: number;
  complexity?: string | null;
  prNumber?: number | null;
  prUrl?: string | null;
  branchName?: string | null;
  lastCommitAt?: string | null;
  prAuthor?: string | null;
  commitMessages?: string[] | null;
  prTitle?: string | null;
  prLink?: string | null;
  commitCount?: number;
  deploymentTag?: string | null;
  deployed?: boolean;
  deployedAt?: string | null;
  deployEnvironment?: string | null;
  prAgeHours?: number | null;
  reviewerDelayHours?: number | null;
  statusChanges?: number;
  pingPongTransitions?: number;
  flags: string[];
  insight?: string | null;
  nudge?: string | null;
  reasoning?: string | null;
  rootCause?: string | null;
  rootCauseAnalysis?: RootCauseAnalysis | null;
  explainabilityFactors?: string[] | null;
  impact?: string | null;
  recommendedAction?: string | null;
  actionOwner?: string | null;
  severity?: string | null;
  trendIndicator?: string | null;
  confidence?: string | null;
  lastUpdated?: string | null;
  dataQuality?: DataQuality | null;
  jiraDataAvailable?: boolean;
  prDataAvailable?: boolean;
  agingBucket?: string | null;
  deliveryRisk?: string | null;
  movementStatus?: string | null;
  timeInStatusLabel?: string | null;
  lastActivityLabel?: string | null;
  viewGroup?: string | null;
  lastNotifiedAt?: string | null;
  stageDurations?: Record<string, number> | null;
  totalTat?: number;
}

export interface ManualNotifyResponse {
  status: string;
  ticketId: string;
  assignee: string;
  messagePreview: string | null;
  lastNotifiedAt?: string | null;
  reason?: string | null;
  messageSource?: string | null;
}

export interface TicketSuggestion {
  ticketId: string;
  reason: string;
  recommendedAction: string;
  suggestedActions?: string[] | null;
}

export interface StageTimelineEntry {
  stage: string;
  hours: number;
}

export interface DeliveryTicketCard {
  ticketId: string;
  title?: string | null;
  assignee?: string | null;
  currentStage?: string | null;
  totalHours?: number;
  estimatedCompletion?: string | null;
  taskComplexity?: string | null;
  timelineNote?: string | null;
  deliveryStatus?: string | null;
  stageTimeline?: StageTimelineEntry[];
  slowStageWarning?: string | null;
}

export interface AgentRunResponse {
  tickets: Ticket[];
  projectSummary: ProjectSummary | null;
  projectHealth: ProjectHealth | null;
  simulation?: boolean;
  generatedAt?: string | null;
  deliveryCards?: DeliveryTicketCard[];
}

export interface DeliveryTrendPoint {
  recordedAt: string;
  avgPrHours: number;
  avgDwellHours: number;
  ticketCount: number;
  doneCount?: number;
  avgTotalTatHours?: number;
  velocityTicketsPerDay?: number;
}

export interface DeliveryTrend {
  snapshots: DeliveryTrendPoint[];
}

export interface TeamAnalyticsOverview {
  totalTeamMembers: number;
  totalActiveTickets: number;
  completedTickets: number;
  ticketsUnderReview: number;
  avgTicketsPerDeveloper: number;
}

export interface TeamMemberAnalytics {
  name: string;
  experience?: string | null;
  avatarHue: number;
  totalTicketsAssigned: number;
  completedTickets: number;
  inProgress: number;
  underReview: number;
  blocked: number;
  performanceLevel: string;
  performanceLabel: string;
  aiInsight: string;
}

export interface GitActivityPerMember {
  assigneeName: string;
  commitsPerDay: number;
  commitsPerWeek: number;
  commitsPerMonth: number;
  prsCreated: number;
  prsMerged: number;
  avgPrReviewTimeHours: number;
  totalCommits: number;
}

export interface CommitsTimeSeriesPoint {
  date: string;
  commits: number;
}

export interface WorkloadBar {
  assigneeName: string;
  activeTicketCount: number;
  highlight: string;
}

export interface TeamAnalyticsResponse {
  overview: TeamAnalyticsOverview;
  members: TeamMemberAnalytics[];
  gitActivityByMember: GitActivityPerMember[];
  commitsOverTime: CommitsTimeSeriesPoint[];
  workloadByAssignee: WorkloadBar[];
  generatedAt?: string | null;
}

export interface AgentChatResponse {
  message: string;
  bullets: string[];
  referencedTicketIds: string[];
  sessionId: string;
  answerSource?: string | null;
}

export interface ChatSessionSummary {
  sessionId: string;
  title: string;
  createdAt: string;
  updatedAt: string;
}

export interface ChatMessageView {
  role: string;
  content: string;
  timestamp: string;
}

export interface ChatHistoryResponse {
  sessionId: string;
  title: string;
  messages: ChatMessageView[];
}

@Injectable({
  providedIn: 'root',
})
export class ApiService {
  private readonly base = '';

  constructor(private readonly http: HttpClient) {}

  private unwrap<T>(fallback: T): (envelope: ApiEnvelope<T>) => T {
    return (envelope) => {
      if (!envelope.success) {
        const msg = envelope.error?.message ?? 'Request failed';
        throw new Error(msg);
      }
      const d = envelope.data;
      return d != null ? d : fallback;
    };
  }

  getTickets(): Observable<Ticket[]> {
    return this.http
      .get<ApiEnvelope<Ticket[]>>(`${this.base}/api/tickets`)
      .pipe(map(this.unwrap<Ticket[]>([])));
  }

  runAgent(): Observable<AgentRunResponse> {
    return this.http
      .post<ApiEnvelope<AgentRunResponse>>(`${this.base}/api/run-agent`, {})
      .pipe(
        map(
          this.unwrap<AgentRunResponse>({
            tickets: [],
            projectSummary: null,
            projectHealth: null,
          }),
        ),
      );
  }

  getInsights(): Observable<AgentRunResponse> {
    return this.http
      .get<ApiEnvelope<AgentRunResponse>>(`${this.base}/api/insights`)
      .pipe(
        map(
          this.unwrap<AgentRunResponse>({
            tickets: [],
            projectSummary: null,
            projectHealth: null,
          }),
        ),
      );
  }

  getSuggestions(): Observable<TicketSuggestion[]> {
    return this.http
      .get<ApiEnvelope<TicketSuggestion[]>>(`${this.base}/api/suggestions`)
      .pipe(map(this.unwrap<TicketSuggestion[]>([])));
  }

  notifyTicket(ticketId: string): Observable<ManualNotifyResponse> {
    const enc = encodeURIComponent(ticketId);
    return this.http
      .post<ApiEnvelope<ManualNotifyResponse>>(`${this.base}/api/notify/${enc}`, {})
      .pipe(
        map(
          this.unwrap<ManualNotifyResponse>({
            status: '',
            ticketId: '',
            assignee: '',
            messagePreview: null,
          }),
        ),
      );
  }

  getDeliveryTrend(): Observable<DeliveryTrend> {
    return this.http
      .get<ApiEnvelope<DeliveryTrend>>(`${this.base}/api/delivery-trend`)
      .pipe(map(this.unwrap<DeliveryTrend>({ snapshots: [] })));
  }

  getScenarioCurrent(): Observable<{ scenario: string }> {
    return this.http
      .get<ApiEnvelope<{ scenario: string }>>(`${this.base}/api/scenario/current`)
      .pipe(map(this.unwrap<{ scenario: string }>({ scenario: 'AMBER' })));
  }

  setScenario(tier: SimulationScenarioTier): Observable<{ scenario: string; message: string }> {
    return this.http
      .post<ApiEnvelope<{ scenario: string; message: string }>>(
        `${this.base}/api/scenario/${tier}`,
        {},
      )
      .pipe(
        map(
          this.unwrap<{ scenario: string; message: string }>({
            scenario: tier,
            message: '',
          }),
        ),
      );
  }

  getTeamAnalytics(): Observable<TeamAnalyticsResponse> {
    return this.http
      .get<ApiEnvelope<TeamAnalyticsResponse>>(`${this.base}/api/team-analytics`)
      .pipe(
        map(
          this.unwrap<TeamAnalyticsResponse>({
            overview: {
              totalTeamMembers: 0,
              totalActiveTickets: 0,
              completedTickets: 0,
              ticketsUnderReview: 0,
              avgTicketsPerDeveloper: 0,
            },
            members: [],
            gitActivityByMember: [],
            commitsOverTime: [],
            workloadByAssignee: [],
          }),
        ),
      );
  }

  postAgentChat(query: string, sessionId?: string | null): Observable<AgentChatResponse> {
    return this.http
      .post<ApiEnvelope<AgentChatResponse>>(`${this.base}/api/agent-chat`, {
        query,
        sessionId: sessionId ?? undefined,
      })
      .pipe(
        map(
          this.unwrap<AgentChatResponse>({
            message: '',
            bullets: [],
            referencedTicketIds: [],
            sessionId: sessionId ?? '',
          }),
        ),
      );
  }

  createChatSession(): Observable<{ sessionId: string }> {
    return this.http
      .post<ApiEnvelope<{ sessionId: string }>>(`${this.base}/api/chat/session`, {})
      .pipe(map(this.unwrap<{ sessionId: string }>({ sessionId: '' })));
  }

  listChatSessions(): Observable<ChatSessionSummary[]> {
    return this.http
      .get<ApiEnvelope<ChatSessionSummary[]>>(`${this.base}/api/chat/sessions`)
      .pipe(map(this.unwrap<ChatSessionSummary[]>([])));
  }

  getChatHistory(sessionId: string): Observable<ChatHistoryResponse> {
    const enc = encodeURIComponent(sessionId);
    return this.http
      .get<ApiEnvelope<ChatHistoryResponse>>(`${this.base}/api/chat/${enc}`)
      .pipe(
        map(
          this.unwrap<ChatHistoryResponse>({
            sessionId: '',
            title: '',
            messages: [],
          }),
        ),
      );
  }

  postChatMessage(sessionId: string, message: string): Observable<AgentChatResponse> {
    const enc = encodeURIComponent(sessionId);
    return this.http
      .post<ApiEnvelope<AgentChatResponse>>(`${this.base}/api/chat/${enc}`, { message })
      .pipe(
        map(
          this.unwrap<AgentChatResponse>({
            message: '',
            bullets: [],
            referencedTicketIds: [],
            sessionId,
          }),
        ),
      );
  }

  postScenario(
    tier: SimulationScenarioTier,
  ): Observable<{ scenario: string; message: string }> {
    return this.http
      .post<ApiEnvelope<{ scenario: string; message: string }>>(
        `${this.base}/api/scenario/${tier}`,
        {},
      )
      .pipe(
        map(
          this.unwrap<{ scenario: string; message: string }>({
            scenario: tier,
            message: '',
          }),
        ),
      );
  }
}

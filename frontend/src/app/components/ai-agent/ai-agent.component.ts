import { CommonModule } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import {
  AfterViewChecked,
  Component,
  ElementRef,
  OnInit,
  ViewChild,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { DomSanitizer, SafeHtml } from '@angular/platform-browser';
import {
  ApiService,
  AgentChatResponse,
  ApiEnvelope,
  ChatMessageView,
  ChatSessionSummary,
} from '../../services/api.service';

const SESSION_STORAGE_KEY = 'pmo_agent_chat_session_id';
const HISTORY_COLLAPSED_KEY = 'pmo_agent_chat_history_collapsed';

interface ChatMessage {
  role: 'user' | 'assistant';
  html: SafeHtml | null;
  plain: string;
  bullets: string[];
  ticketIds: string[];
  sentAt: Date | null;
}

@Component({
  selector: 'app-ai-agent',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './ai-agent.component.html',
  styleUrl: './ai-agent.component.scss',
})
export class AiAgentComponent implements OnInit, AfterViewChecked {
  @ViewChild('scrollArea') scrollArea?: ElementRef<HTMLDivElement>;

  messages: ChatMessage[] = [];
  chatSessions: ChatSessionSummary[] = [];
  currentSessionId: string | null = null;
  input = '';
  sending = false;
  sessionLoading = true;
  error: string | null = null;
  /** When true, the conversation list sidebar is hidden. */
  historyCollapsed = false;
  readonly starterPrompts = [
    'Show blocked tickets',
    'Who is overloaded?',
    'What are the riskiest tickets?',
    'What should we do next?',
  ];
  private shouldScroll = false;

  constructor(
    private readonly api: ApiService,
    private readonly sanitizer: DomSanitizer,
  ) {}

  ngOnInit(): void {
    const collapsed = localStorage.getItem(HISTORY_COLLAPSED_KEY);
    this.historyCollapsed = collapsed === '1' || collapsed === 'true';
    void this.bootstrapSessions();
  }

  toggleHistoryCollapsed(): void {
    this.historyCollapsed = !this.historyCollapsed;
    localStorage.setItem(HISTORY_COLLAPSED_KEY, this.historyCollapsed ? '1' : '0');
    this.shouldScroll = true;
  }

  showStarterSuggestions(): boolean {
    return (
      !this.sending &&
      this.messages.length === 1 &&
      this.messages[0].role === 'assistant'
    );
  }

  applyStarterPrompt(text: string): void {
    this.input = text;
    this.error = null;
  }

  private bootstrapSessions(): void {
    this.sessionLoading = true;
    this.error = null;
    this.api.listChatSessions().subscribe({
      next: (sessions) => {
        this.chatSessions = sessions;
        const stored = localStorage.getItem(SESSION_STORAGE_KEY);
        let pick: string | null = null;
        if (stored && sessions.some((s) => s.sessionId === stored)) {
          pick = stored;
        } else if (sessions.length > 0) {
          pick = sessions[0].sessionId;
        }
        if (pick) {
          this.selectSession(pick);
        } else {
          this.startFreshSession();
        }
      },
      error: () => {
        this.startFreshSession();
      },
    });
  }

  private startFreshSession(): void {
    this.api.createChatSession().subscribe({
      next: (c) => {
        const now = new Date().toISOString();
        this.chatSessions = [
          {
            sessionId: c.sessionId,
            title: 'New chat',
            createdAt: now,
            updatedAt: now,
          },
        ];
        this.selectSession(c.sessionId);
      },
      error: (e: unknown) => {
        this.sessionLoading = false;
        this.error = this.errMessage(e, 'Could not start chat');
      },
    });
  }

  selectSession(sessionId: string): void {
    this.currentSessionId = sessionId;
    localStorage.setItem(SESSION_STORAGE_KEY, sessionId);
    this.error = null;
    this.api.getChatHistory(sessionId).subscribe({
      next: (h) => {
        this.messages = this.mapHistoryToMessages(h.messages);
        if (this.messages.length === 0) {
          this.pushWelcome();
        }
        this.sessionLoading = false;
        this.shouldScroll = true;
      },
      error: (e: unknown) => {
        this.sessionLoading = false;
        this.error = this.errMessage(e, 'Could not load chat');
        this.shouldScroll = true;
      },
    });
  }

  newChat(): void {
    this.error = null;
    this.api.createChatSession().subscribe({
      next: (c) => {
        const now = new Date().toISOString();
        const row: ChatSessionSummary = {
          sessionId: c.sessionId,
          title: 'New chat',
          createdAt: now,
          updatedAt: now,
        };
        this.chatSessions = [row, ...this.chatSessions.filter((s) => s.sessionId !== c.sessionId)];
        this.selectSession(c.sessionId);
      },
      error: (e: unknown) => {
        this.error = this.errMessage(e, 'Could not create chat');
      },
    });
  }

  private refreshSessionList(): void {
    this.api.listChatSessions().subscribe({
      next: (s) => (this.chatSessions = s),
      error: () => {},
    });
  }

  private mapHistoryToMessages(rows: ChatMessageView[]): ChatMessage[] {
    const out: ChatMessage[] = [];
    for (const m of rows) {
      const role = m.role?.toLowerCase() === 'user' ? 'user' : 'assistant';
      const sentAt = m.timestamp ? new Date(m.timestamp) : null;
      const content = m.content ?? '';
      if (role === 'user') {
        out.push({
          role: 'user',
          html: null,
          plain: content,
          bullets: [],
          ticketIds: [],
          sentAt,
        });
      } else {
        out.push({
          role: 'assistant',
          html: this.richHtml(content),
          plain: content,
          bullets: [],
          ticketIds: this.extractTicketIds(content),
          sentAt,
        });
      }
    }
    return out;
  }

  private extractTicketIds(text: string): string[] {
    const re = /\bSIM-\d{4}\b/g;
    const set = new Set<string>();
    let m: RegExpExecArray | null;
    while ((m = re.exec(text)) !== null) {
      set.add(m[0]);
    }
    return [...set].slice(0, 12);
  }

  private pushWelcome(): void {
    this.pushAssistant(
      'I analyze **assignees, delivery risk, blockers, and bottlenecks** from the same portfolio snapshot as your dashboard. Ask in plain language — I remember this conversation.',
      [],
      [],
      new Date(),
    );
  }

  ngAfterViewChecked(): void {
    if (this.shouldScroll) {
      this.shouldScroll = false;
      const el = this.scrollArea?.nativeElement;
      if (el) {
        el.scrollTop = el.scrollHeight;
      }
    }
  }

  send(): void {
    const q = this.input.trim();
    if (!q || this.sending || !this.currentSessionId) {
      return;
    }
    this.input = '';
    this.error = null;
    this.messages.push({
      role: 'user',
      html: null,
      plain: q,
      bullets: [],
      ticketIds: [],
      sentAt: new Date(),
    });
    this.shouldScroll = true;
    this.sending = true;
    this.api.postChatMessage(this.currentSessionId, q).subscribe({
      next: (r: AgentChatResponse) => {
        this.pushAssistant(r.message, r.bullets ?? [], r.referencedTicketIds ?? [], new Date());
        this.sending = false;
        this.shouldScroll = true;
        this.refreshSessionList();
      },
      error: (e: unknown) => {
        this.sending = false;
        this.error = this.errMessage(e, 'Chat request failed');
        this.shouldScroll = true;
      },
    });
  }

  private pushAssistant(
    message: string,
    bullets: string[],
    ticketIds: string[],
    sentAt: Date,
  ): void {
    this.messages.push({
      role: 'assistant',
      html: this.richHtml(message),
      plain: message,
      bullets,
      ticketIds,
      sentAt,
    });
  }

  richHtml(text: string): SafeHtml {
    let s = text
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>');
    s = s.replace(/(SIM-\d+)/g, '<span class="ticket-id">$1</span>');
    s = s.replace(/\n/g, '<br/>');
    return this.sanitizer.bypassSecurityTrustHtml(s);
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
    if (e instanceof Error) {
      return e.message || fallback;
    }
    return fallback;
  }
}

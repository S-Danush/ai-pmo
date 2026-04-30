# -*- coding: utf-8 -*-
"""Emit Java SimScenario lines for SimulationDataService. Run: python gen_rows.py > rows_out.txt"""
titles = [
    "KYC verification failing for PAN validation - LOS onboarding",
    "Loan disbursement delay due to sponsor bank API timeout",
    "EMI schedule mismatch in LMS repayment module",
    "Credit underwriting rule misfiring for salaried applicants",
    "Ledger reconciliation inconsistency between LOS and LMS",
    "NACH mandate registration failing for joint accounts",
    "Co-lending partner pricing - bank API confirmation",
    "Loan foreclosure settlement - code review queue",
    "Income document OCR - reviewer capacity",
    "NBFC regulatory reporting - compliance review",
    "LOS-core banking cutover - infra sign-off",
    "LMS portal refresh - design approval",
    "Top-up loan eligibility - policy finalization",
    "Credit bureau score refresh - vendor freeze",
    "Bulk disbursement file - sponsor bank timeout",
    "EMI bounce handling - QA rework",
    "Loan statement PDF layout - QA feedback",
    "Loan restructuring workflow - collections scope",
    "Collateral release automation - discovery",
    "Interest rate revision propagation - LMS",
    "LMS audit trail export - owner TBD",
    "Prepayment penalty calculation - implementation",
    "Loan sanction letter merge fields - implementation",
    "LOS dashboard KPI drill-down - build start",
    "Minor sanction letter wording - compliance",
    "Annual LMS maintenance window - comms",
    "A/B test loan funnel - experience design",
    "Mobile OTP fallback - login released",
    "Regulatory audit log export - cold storage released",
    "Card tokenization scope - collections released",
    "UPI mandate retry storm - LOS payments",
    "Gold loan LTV recalculation - appraisal API",
    "Co-borrower consent SMS - LMS notifications",
    "Foreclosure notice template - regional variants",
    "NPA tagging rules - collections engine",
    "Partner webhook DLQ - replay tooling",
    "Statement interest accrual - rounding fix",
    "Delinquency bucket migration - batch job",
    "Customer self-serve payoff quote - LMS API",
    "Branch cash deposit limits - policy engine",
    "Sponsor bank cert rotation - TLS handshake",
    "LOS field audit - PII masking",
    "LMS amortization holiday - COVID carryover",
    "Cross-sell insurance opt-in - journey copy",
    "Repo rate shock scenario - stress test UI",
    "Collections IVR callback - queue depth",
    "Loan closure certificate - PDF merge",
    "Merchant EMI subvention - reconciliation",
    "Risk scorecard v3 shadow mode - underwriting",
    "Internal tool SSO - SAML bridge",
    "Data lake LOS snapshots - incremental load",
    "Mobile app dark mode - LMS statements",
    "BNPL eligibility pilot - LOS rules",
    "Regulatory returns Q4 - field mapping",
    "Stress capital reporting - LMS extracts",
]
A = (
    ["Danush S"] * 9
    + ["Dhanush Balaji"] * 8
    + ["Sindhu Manickam"] * 4
    + ["Naveenchandhar"] * 4
    + ["Abdul Rasheed"] * 3
    + ["Batladinne Mythilipriya"] * 2
    + ["Harshini Dhanasekar"] * 3
    + ["Madhumathi Muralidharan"] * 3
    + ["Mohamed Afridi"] * 3
    + ["Ravindra Dayal"] * 1
)
A[20] = "Unassigned"
DoneA = [
    "Danush S",
    "Dhanush Balaji",
    "Sindhu Manickam",
    "Naveenchandhar",
    "Abdul Rasheed",
    "Batladinne Mythilipriya",
    "Harshini Dhanasekar",
    "Madhumathi Muralidharan",
    "Mohamed Afridi",
    "Ravindra Dayal",
    "Danush S",
    "Dhanush Balaji",
]


def line(i, idn, title, asg, pri, wf, hrs, prs, prt, dep, bnc, cx, bn):
    return (
        f'            new SimScenario({i}, "{idn}", "{title}", "{asg}", "{pri}", '
        f'WfStatus.{wf}, {hrs}, "{prs}", {prt}, "{dep}", {bnc}, "{cx}", "{bn}"),'
    )


rows = []
for i in range(40):
    asg = A[i]
    tid = f"SIM-{1001 + i}"
    ti = titles[i]
    pri = "Medium"
    wf = "IN_PROGRESS"
    hrs = 12 + (i % 11)
    prs = "OPEN"
    prt = 16 + (i % 9) * 2
    dep = "NONE"
    bnc = i % 3
    cx = "MEDIUM"
    bn = "Team throughput vs commitments"
    if i <= 3:
        pri, wf, hrs, prs, prt, dep, bnc, cx, bn = (
            "High",
            "IN_PROGRESS",
            14 + i,
            "NOT_CREATED",
            0,
            "NONE",
            0,
            "MEDIUM",
            "Implementation not started yet",
        )
    elif i == 4:
        pri, wf, hrs, prs, prt, bn = (
            "High",
            "IN_PROGRESS",
            18,
            "NOT_CREATED",
            0,
            "Implementation not started yet",
        )
    elif i in (5, 6, 7):
        wf, prs, prt = "REVIEW", "IN_REVIEW", 26 + i * 3
        hrs = 15 + i
        bn = "Code review is taking longer than usual"
        if i == 6:
            dep, cx, bnc = "API", "COMPLEX", 1
    elif i in (8, 9, 10):
        wf, prs, prt, hrs = "BLOCKED", "OPEN", 12 + i, 11 + i
        if i == 8:
            dep, bn = "EXTERNAL_TEAM", "Waiting on partner or compliance sign-off"
        elif i == 9:
            dep, bn = "API", "Waiting on external bank API"
        else:
            dep, bn = "DESIGN", "Business rule not finalized"
        pri = "High" if i != 9 else "Medium"
    elif i == 11:
        wf, prs, hrs, prt, dep, bn = (
            "BLOCKED",
            "NOT_CREATED",
            13,
            0,
            "DESIGN",
            "Business rule not finalized",
        )
    elif i == 12:
        wf, prs, hrs, prt, bn = (
            "BLOCKED",
            "NOT_CREATED",
            16,
            0,
            "Business rule not finalized",
        )
    elif i in (13, 14):
        # Keep dwell <= 22 so BLOCKED + STUCK does not force HIGH severity (portfolio RED).
        wf, prs, prt, hrs = "BLOCKED", "OPEN", 14 + i, 13 + (i - 13) * 2
        dep, bn = "API", "Waiting on external bank API"
        bnc = 1 if i == 14 else 0
    elif i in (15, 16, 17):
        wf, prs, prt = "IN_PROGRESS", "OPEN", 20 + i
        bnc = 3 if i == 15 else 4 if i == 16 else 3
        bn = (
            "Rework between QA and engineering"
            if i <= 16
            else "Business rule not finalized"
        )
        dep = "DESIGN" if i == 17 else "NONE"
        cx = "COMPLEX" if i >= 16 else "MEDIUM"
    elif i in (18, 19, 20):
        wf, prs, hrs, prt = "BACKLOG", "NOT_CREATED", 10 + i % 3, 0
        bn = "Competing backlog priorities"
        dep = "API" if i == 19 else "NONE"
        pri = "High" if i == 19 else "Medium"
        if i == 20:
            bn = "No owner assigned"
    elif i == 21:
        wf, prs, prt, hrs = "IN_PROGRESS", "OPEN", 22, 14
    elif i == 22:
        wf, prs, hrs, prt, bn = (
            "IN_PROGRESS",
            "NOT_CREATED",
            17,
            0,
            "Team throughput vs commitments",
        )
    elif i == 23:
        wf, prs, hrs, prt, bn = (
            "IN_PROGRESS",
            "NOT_CREATED",
            12,
            0,
            "Implementation not started yet",
        )
    elif i == 24:
        wf, prs, prt, hrs = "REVIEW", "IN_REVIEW", 22, 16
        bn = "Team throughput vs commitments"
    elif i in (25, 26):
        wf, prs, hrs, prt = "BACKLOG", "NOT_CREATED", 11, 0
        bn = "Competing backlog priorities"
        dep = "DESIGN" if i == 26 else "NONE"
    elif i in range(27, 40):
        wf, prs, prt = "IN_PROGRESS", "OPEN", 18 + (i % 5)
        hrs = 11 + (i % 7)
        bn = "Team throughput vs commitments"
        if i == 33:
            wf, prs, hrs, prt = "IN_PROGRESS", "NOT_CREATED", 19, 0
        if i == 35:
            wf, prs, hrs, prt = "IN_PROGRESS", "NOT_CREATED", 20, 0
    if i in (28, 29):
        pri = "Low"
    if asg == "Ravindra Dayal":
        pri, wf, prs, prt, hrs = "Low", "IN_PROGRESS", "OPEN", 14, 12
    rows.append(line(i, tid, ti, asg, pri, wf, hrs, prs, prt, dep, bnc, cx, bn))
for i in range(40, 52):
    asg = DoneA[i - 40]
    tid = f"SIM-{1001 + i}"
    ti = titles[i]
    rows.append(
        line(
            i,
            tid,
            ti,
            asg,
            "Medium" if i % 2 == 0 else "Low",
            "DONE",
            10 + i % 6,
            "MERGED",
            0,
            "NONE",
            0,
            "SIMPLE",
            "None - progressing normally",
        )
    )
print("\n".join(rows))

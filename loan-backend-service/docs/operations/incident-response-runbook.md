# Incident response runbook

## Severity

- Page: unknown core banking outcome, reconciliation mismatch, readiness down, suspected credential leak, or duplicate financial posting.
- Ticket: elevated 5xx or p95 above 2 seconds without an unknown financial outcome.

## First actions

1. Record the correlation id, time, and affected loan or transaction reference.
2. Keep the current logs. Production logs are JSON on stdout and include `correlationId`.
3. If a secret was exposed, rotate it in the secrets manager and restart. The production profile rejects the local example values, so the replacement must be a new value.
4. If disbursement or repayment behaviour is uncertain, stop new financial traffic. Leave existing `UNKNOWN` rows for enquiry. Do not delete them and do not post a second instruction with a new idempotency key until enquiry shows the first one was not posted.

## Evidence

Capture the audit row, the `core_banking_transactions` row, the core banking provider reference, and the webhook receipt. Audit payloads do not store passwords, tokens, or raw identity numbers. Do not copy those values into the ticket.

## Closure

Write the customer impact, the ledger impact, the time traffic was restored, and the follow-up control. A duplicate posting is not closed until the reversal and the replacement are both reconciled.

# Financial reconciliation

A disbursement or repayment is posted once for an idempotency key. A second request with the same key returns the existing instruction. A second open disbursement for the same application is rejected. Repayment allocation rows are unique per account and schedule item. Posted journals are corrected by reversal and replacement, not by editing the original lines.

## Daily procedure

1. Compare posted repayment and disbursement totals with the core banking statement for the business date.
2. Investigate every `UNKNOWN`, `SUBMITTED`, and reconciliation `MISMATCH` row before the next business day.
3. For `UNKNOWN`, call enquiry. Confirm only when the provider shows the posting. Fail the local instruction only when the provider shows it was not posted. Then, and only then, may an officer submit a new instruction.
4. A mismatch alert (`core_banking_reconciliation_mismatches_total`) means the provider amount and the local amount differ, or the provider posting is missing while the local state is successful. Do not auto-post the difference.
5. Reversals use their own idempotency key and reference the original instruction. A reversed instruction does not accept another confirmation.

## Concurrent posts

The instruction insert is unique on the idempotency key. The payment claim is single-use. Two concurrent callers share one core banking submission. The unit tests cover that race for disbursement and repayment. The database unique indexes are the backstop when two application instances race.

## Unbalanced journals

Journal lines for one entry must balance in the entry currency before the entry is treated as posted. A reconciliation difference is recorded on the batch. It is not forced to balance by editing a posted line.

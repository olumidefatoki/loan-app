# React integration

The web app calls the same `/api/v1` contract as Flutter. Base URL comes from configuration. Send `Authorization: Bearer` after login, and send `X-Correlation-Id` on every call. See `authentication.md` for token storage and refresh.

## Envelope

Unwrap `data` only when `success` is true and `code` is `OK`. Branch failures on `code` using `error-codes.md`. Created resources return 201. A report export that is still running, and a configuration change waiting for a second administrator, return 202 with the same envelope and an id to poll.

Dates and timestamps are strings. Instants look like `2026-11-24T13:01:43.337457Z`. Local dates are `YYYY-MM-DD`.

`openapi.json` is OpenAPI 3.1. Generate a typed client with OpenAPI Generator 7 (`typescript-fetch`) or `openapi-typescript`, then point the client at this file. Postman imports the same file.

## Money and currency

Amounts are JSON numbers. The platform books `NGN`. Loan accounts, applications, offers, and collateral use `currencyCode`. Dashboards and several reports use `currency`. Profile income, expenses, and some application financial lines are numbers without a sibling currency field; they are naira amounts in this platform.

Parse money with a decimal type such as `decimal.js`. `JSON.parse` turns the value into a JavaScript number, which is not a safe decimal for every amount the API allows. Do not change the client to expect decimal strings. The service emits numbers today, and switching the wire format would break existing callers. That choice is recorded in `missing-functionality.md`.

## Pagination

`page` starts at 0. `size` is 1 to 100 and defaults to 20. Every page includes `page`, `size`, and `total`. The row array is not always named `items`.

| Response | Row array |
| --- | --- |
| `PageResponse*` , `AuditPage` | `items` |
| `CustomerLoanPage` | `loans` |
| `CustomerRepaymentPage` | `repayments` |
| `DetailPage` inside report lines | `lines` |

`AuditPage` also returns `chainIntact`. `CustomerRepaymentPage` also returns `periodBounded`, `periodStart`, and `periodEnd`. There is no `totalPages` field. Compute it from `total` and `size`.

## Customer screens

Use these reads for the signed-in customer. Lists that the customer uses for navigation include ids. The dashboard summaries do not.

| Screen | Call | Fields the UI can bind |
| --- | --- | --- |
| Home | `GET /api/v1/dashboard/customer` | `currency`, outstanding amounts, `nextRepaymentDate`, `nextRepaymentAmount`, `statement`, `applications`, `applicationCount` |
| Profile | `GET /api/v1/customers/me` | Identity is masked. Contact, addresses, profiles, and KYC state are on `CustomerResponse`. |
| Applications | `GET /api/v1/loan-applications/my-applications` | `id`, `reference`, `status`, `amount`, `currencyCode`, product, tenure |
| Application detail | `GET /api/v1/loan-applications/{id}` | Draft fields, financials, obligations, guarantors, collateral, document checklist |
| Offers | `GET /api/v1/customers/me/loan-offers` | Offer summary page |
| Loans | `GET /api/v1/loan-accounts` | `id`, `reference`, `currencyCode`, balances, dates |
| Loan detail | `GET /api/v1/loan-accounts/{id}`, `/balance`, `/schedule`, `/statement` | Statement `repayments[]` include receipt ids |
| Notifications | `GET /api/v1/notifications` | In-app items. Mark read with `POST /api/v1/notifications/{id}/read` |

`GET /api/v1/dashboard/customer/loans` and `/repayments` are summaries keyed by `reference`. They do not include the UUID required by the detail routes. Open a loan or application from the list endpoints above.

Product selection uses `GET /api/v1/loan-products` and `GET /api/v1/loan-products/{id}/calculator-config`. There is no quote endpoint. The schedule the customer eventually signs is produced by the server when the offer is generated.

Origination is `POST` and `PUT /api/v1/loan-applications`, then `POST /{id}/submit`, `POST /{id}/withdraw`, and `POST /api/v1/loan-offers/{id}/accept` or `/decline`. Repayment is `POST /api/v1/repayments` with `idempotencyKey`, `amount` as a JSON number, and `channel` of `BANK_TRANSFER`, `INTERNAL_DEBIT`, `PAYMENT_GATEWAY`, or `BRANCH`. Keep the idempotency key stable for retries.

## Staff portal

The administration portal uses the same bearer token from `POST /api/v1/admin/auth/login` and, when required, MFA. Queues and configuration the portal needs are already on the API:

- Applications: `GET /api/v1/admin/loan-applications/queue` and `POST /{id}/transitions`
- Credit: `GET /api/v1/admin/credit-assessments/queue` and `/api/v1/credit-assessments/**`
- Approvals: `GET /api/v1/approvals/my-queue` and the decision posts
- Products, matrices, templates, users, roles, branches, calendars, feature flags, and system configuration under `/api/v1/admin/**`
- Collections: `/api/v1/collections/cases`
- Compliance and complaints: `/api/v1/admin/compliance/**` and `/api/v1/complaints`
- Dashboards and reports: `/api/v1/dashboard/**` and `/api/v1/reports/**`
- Audit: `/api/v1/admin/audit-logs`

A `403 FORBIDDEN` means the signed-in staff member lacks the permission or branch. The OpenAPI security requirement records the bearer token. The permission check is enforced in the service, so a generated client will not hide a button the user cannot call.

## Browser constraints

CORS allows `Authorization`, `Content-Type`, and `X-Correlation-Id` for the origins in `APP_ALLOWED_ORIGINS`. It does not allow `X-Client-Channel`. Omit that header from the browser. Customer creation, profile save, and application creation then store `WEB`.

Downloads must be handled as blobs. Do not run them through the JSON envelope parser.

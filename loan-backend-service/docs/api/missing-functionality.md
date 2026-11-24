# Missing API functionality

This review compared the implemented controllers with the customer and staff screens those controllers describe. The React and Flutter applications are not in this repository, so a screen is treated as required when a controller, tag, or response is already aimed at that screen. No existing request or response field was renamed.

## Contract gaps that clients must handle now

These are implemented behaviours. Changing them would break a client that already follows the current JSON.

| Gap | What the API does | Backward-compatible direction |
| --- | --- | --- |
| Money is a JSON number | Jackson writes `BigDecimal` as a JSON number. Currency is a separate string, usually `currencyCode`. The customer dashboard and `CustomerApplication` use `currency`. | A later version can add decimal strings. Replacing the number in v1 would break numeric parsers. |
| Two success and error shapes | Success has `data` and no `path`. `ApiError` has `errors` and `path` and no `data`. | Keep both. Clients branch on `success`. |
| Page collection names differ | Most lists use `items`. Customer loans use `loans`, customer repayments use `repayments`, report lines use `lines`. `AuditPage` adds `chainIntact`. | Keep the current names. New lists should use `items`, `page`, `size`, and `total`. |
| Some lists have no page | Roles, permissions, calendars, feature flags, agreement templates, scoring models, approval matrices, delegations, application documents, offer schedules, and notification preferences return an array in `data`. | Adding `page` and `size` later is compatible if the default remains the full list. |
| Downloads are raw bytes | Successful document and export responses are not the JSON envelope. Springdoc had described `byte[]` as base64. The published spec now says `format: binary`. | The wire format stays raw bytes. |
| Error codes are shared | Many rules return `CONFLICT` or `VALIDATION_ERROR`. The specific text is in `message` and `errors`. | New codes can be added. Existing codes stay. |
| Channel header is narrow | `X-Client-Channel` is stored for customer create, profile save, and application create. It defaults to `WEB`. Every other route ignores it. Behaviour does not change between web and mobile. | Mobile must send `MOBILE` on those three writes. |
| Offer acceptance has two routes | `POST /api/v1/loan-offers/{id}/accept` records agreement version, identity reference, and checksum. `POST /api/v1/loan-applications/{id}/accept-offer` only sets the application status to `OFFER_ACCEPTED`. | Customer screens should call the loan-offer route. Removing the application route would break a client that already calls it. |

## Missing customer routes

| Screen need | What exists | What is missing |
| --- | --- | --- |
| View an uploaded application document | List metadata at `GET /api/v1/loan-applications/{id}/documents` | A byte download for that document id |
| Register this phone for push | Preferences and in-app inbox | A device-token route. Contact lookup always passes a null push token, so push is skipped |
| Change password while signed in | Forgotten-password and reset-password | An authenticated change that checks the current password |
| Open a dashboard loan | `CustomerLoan` has reference, product, status, balances, and dates | `loanAccountId` and `currencyCode` on that row. The client calls `GET /api/v1/loan-accounts` to get the id |
| Download a customer statement file | JSON statement at `GET /api/v1/loan-accounts/{id}/statement` | A PDF or CSV statement. The agreement PDF route does not cover statements |
| Mark the inbox read | `POST /api/v1/notifications/{id}/read` | Mark-all-read |

Customer repayment, settlement quote, balance, schedule, complaints, KYC, account verification, and notification preferences are present and are not channel-specific.

## Missing staff routes

| Screen need | What exists | What is missing |
| --- | --- | --- |
| Staff forgot password | Customer `/api/v1/auth/forgot-password` and `/reset-password` also reset a staff user, because the service does not filter principal type | Staff-named routes under `/api/v1/admin/auth`. The portal can call the customer routes today |
| Staff change password | Reset via OTP | An authenticated change-password route |
| Application evidence preview | Metadata list | The same missing application-document download |
| Long-running work other than exports | Export id and status. Core banking keeps an instruction id and `UNKNOWN` until `POST /api/v1/admin/integrations/core-banking/transactions/{id}/enquiry` | A single job resource. Repayments and disbursements return their own ids and are idempotent through the body key |

The administration portal otherwise has users, roles, permissions, sessions, branches, configuration maker-checker, calendars, feature flags, products, applications, credit, collateral, guarantors, approvals, offers, core banking operations, servicing, journals, collections, restructuring, compliance, complaints, notification templates, audit, dashboards, and report exports.

## Traceable long-running work

| Operation | Reference the client keeps |
| --- | --- |
| Report export | `data.id`. Poll `GET /api/v1/reports/exports/{id}`. HTTP 202 means `PENDING`. Download with `data.token`. |
| Repayment, settlement, reversal | The repayment id, plus the caller-supplied `idempotencyKey` in the JSON body. |
| Core banking instruction | The core transaction id. Poll or call enquiry while the state is `UNKNOWN`. A timed-out financial instruction is not retried. |

## OpenAPI

`docs/api/openapi.json` contains the v1 routes, bearer security, the correlation header, `ApiError` for the standard error statuses, and examples for registration, login, refresh, repayment, and export. Import that file into Postman. Generate clients with a tool that reads OpenAPI 3.1.

Live Swagger is enabled on the local and development profiles at `/v3/api-docs/v1`. Staging and production leave it disabled. Ship the committed file to frontend builds.

The error responses were added to the specification. They describe the handler that already runs. They do not add new status codes.

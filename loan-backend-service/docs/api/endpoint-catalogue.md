# API endpoint catalogue

Source: `docs/api/openapi.json`, exported from the running springdoc group `v1`.
258 operations on 208 paths.
Every operation accepts optional `X-Correlation-Id` and documents the error envelope.
Audience is the channel that may call the route. Customer and staff share a route when the service authorises both.

## Accounting

| Method | Path | Access | Success | Summary |
| --- | --- | --- | --- | --- |
| `GET` | `/api/v1/admin/accounting/journals` | Bearer | 200 | List journals. Posted journals are immutable |
| `GET` | `/api/v1/admin/accounting/journals/{id}` | Bearer | 200 | Get a journal and its lines |
| `POST` | `/api/v1/admin/accounting/journals/{id}/approve` | Bearer | 200 | Approve a draft journal. The approver must be a different officer from the requester |
| `GET` | `/api/v1/admin/accounting/reconciliation` | Bearer | 200 | Reconcile confirmed servicing collections with core banking cash |

## Approval configuration

| Method | Path | Access | Success | Summary |
| --- | --- | --- | --- | --- |
| `GET` | `/api/v1/admin/approval-delegations` | Bearer | 200 | List delegated approval limits |
| `POST` | `/api/v1/admin/approval-delegations` | Bearer | 200 | Grant a delegated approval limit |
| `DELETE` | `/api/v1/admin/approval-delegations/{id}` | Bearer | 200 | Revoke a delegated approval limit |
| `GET` | `/api/v1/admin/approval-matrices` | Bearer | 200 | List approval matrices |
| `POST` | `/api/v1/admin/approval-matrices` | Bearer | 200 | Create an approval matrix draft |
| `GET` | `/api/v1/admin/approval-matrices/{id}` | Bearer | 200 | Get an approval matrix and its versions |
| `PUT` | `/api/v1/admin/approval-matrices/{id}` | Bearer | 200 | Update the draft approval matrix |
| `DELETE` | `/api/v1/admin/approval-matrices/{id}` | Bearer | 200 | Delete an unused draft matrix |
| `POST` | `/api/v1/admin/approval-matrices/{id}/activate` | Bearer | 200 | Activate the draft matrix version |

## Audit

| Method | Path | Access | Success | Summary |
| --- | --- | --- | --- | --- |
| `GET` | `/api/v1/admin/audit-logs` | Bearer | 200 | List audit records and report whether the hash chain is intact |
| `GET` | `/api/v1/admin/audit-logs/{id}` | Bearer | 200 | Get one audit record |

## Branches

| Method | Path | Access | Success | Summary |
| --- | --- | --- | --- | --- |
| `GET` | `/api/v1/admin/branches` | Bearer | 200 | List branches |
| `POST` | `/api/v1/admin/branches` | Bearer | 200 | Create a branch |
| `GET` | `/api/v1/admin/branches/{id}` | Bearer | 200 | Get a branch |
| `PUT` | `/api/v1/admin/branches/{id}` | Bearer | 200 | Update a branch |
| `DELETE` | `/api/v1/admin/branches/{id}` | Bearer | 200 | Delete a branch that is not referenced |

## Business calendars

| Method | Path | Access | Success | Summary |
| --- | --- | --- | --- | --- |
| `GET` | `/api/v1/admin/business-calendars` | Bearer | 200 | List business calendars |
| `POST` | `/api/v1/admin/business-calendars` | Bearer | 200 | Create a business calendar |
| `GET` | `/api/v1/admin/business-calendars/{id}` | Bearer | 200 | Get a business calendar and its holidays |
| `PUT` | `/api/v1/admin/business-calendars/{id}` | Bearer | 200 | Update a business calendar |
| `DELETE` | `/api/v1/admin/business-calendars/{id}` | Bearer | 200 | Delete a business calendar and its holidays |
| `POST` | `/api/v1/admin/business-calendars/{id}/holidays` | Bearer | 200 | Add a public holiday |
| `DELETE` | `/api/v1/admin/business-calendars/{id}/holidays/{holidayId}` | Bearer | 200 | Remove a public holiday |

## Collateral

| Method | Path | Access | Success | Summary |
| --- | --- | --- | --- | --- |
| `GET` | `/api/v1/collaterals` | Bearer | 200 | List collateral |
| `POST` | `/api/v1/collaterals` | Bearer | 200 | Register collateral |
| `GET` | `/api/v1/collaterals/{id}` | Bearer | 200 | Get collateral |
| `PUT` | `/api/v1/collaterals/{id}` | Bearer | 200 | Update collateral details |
| `POST` | `/api/v1/collaterals/{id}/documents` | Bearer | 200 | Upload a collateral document |
| `GET` | `/api/v1/collaterals/{id}/documents/{documentId}` | Bearer | 200 | Download a collateral document |
| `POST` | `/api/v1/collaterals/{id}/release` | Bearer | 200 | Release collateral |
| `POST` | `/api/v1/collaterals/{id}/valuations` | Bearer | 200 | Submit a valuation |
| `POST` | `/api/v1/collaterals/{id}/valuations/{valuationId}/decision` | Bearer | 200 | Approve or reject a valuation |
| `POST` | `/api/v1/collaterals/{id}/verify` | Bearer | 200 | Verify collateral |

## Collections

| Method | Path | Access | Success | Summary |
| --- | --- | --- | --- | --- |
| `GET` | `/api/v1/collections/cases` | Bearer | 200 | List collection cases visible to the caller |
| `GET` | `/api/v1/collections/cases/{id}` | Bearer | 200 | Get a collection case, including contact history, promises, escalations, and recoveries |
| `POST` | `/api/v1/collections/cases/{id}/activities` | Bearer | 200 | Record a customer contact or collection activity. The summary must not be used to store identity numbers |
| `POST` | `/api/v1/collections/cases/{id}/assign` | Bearer | 200 | Assign a collection case to an officer and branch |
| `POST` | `/api/v1/collections/cases/{id}/close` | Bearer | 200 | Close a collection case. Uncollectible and restructured outcomes do not erase accrued obligations |
| `POST` | `/api/v1/collections/cases/{id}/escalate` | Bearer | 200 | Escalate a collection case |
| `POST` | `/api/v1/collections/cases/{id}/promise-to-pay` | Bearer | 200 | Record a promise to pay |
| `POST` | `/api/v1/collections/cases/{id}/recoveries` | Bearer | 200 | Record a recovery against a posted repayment. This does not post cash or reduce the loan balance |

## Collections reports

| Method | Path | Access | Success | Summary |
| --- | --- | --- | --- | --- |
| `GET` | `/api/v1/reports/collections` | Bearer | 200 | Collections performance, promises, recoveries, and exposure |
| `GET` | `/api/v1/reports/par` | Bearer | 200 | Portfolio at Risk for 30, 60, and 90 days |

## Complaints

| Method | Path | Access | Success | Summary |
| --- | --- | --- | --- | --- |
| `GET` | `/api/v1/complaints` | Bearer | 200 | List complaints visible to the caller |
| `POST` | `/api/v1/complaints` | Bearer | 200 | Submit a complaint |
| `GET` | `/api/v1/complaints/{id}` | Bearer | 200 | Get a complaint and its response history |
| `POST` | `/api/v1/complaints/{id}/activities` | Bearer | 200 | Add a complaint response, assignment, escalation, or resolution |

## Compliance

| Method | Path | Access | Success | Summary |
| --- | --- | --- | --- | --- |
| `GET` | `/api/v1/admin/compliance/alerts` | Bearer | 200 | List compliance alerts |
| `GET` | `/api/v1/admin/compliance/cases` | Bearer | 200 | List compliance cases |
| `GET` | `/api/v1/admin/compliance/cases/{id}` | Bearer | 200 | Get a compliance case and its investigation history |
| `POST` | `/api/v1/admin/compliance/cases/{id}/activities` | Bearer | 200 | Record an investigation note. Notes are retained with the case. |
| `POST` | `/api/v1/admin/compliance/cases/{id}/assign` | Bearer | 200 | Assign a compliance case |
| `POST` | `/api/v1/admin/compliance/cases/{id}/escalate` | Bearer | 200 | Escalate a compliance case |
| `POST` | `/api/v1/admin/compliance/cases/{id}/resolve` | Bearer | 200 | Clear or reject a compliance case after review. Sanctions matches are not cleared without an investigation note. |

## Core banking integration

| Method | Path | Access | Success | Summary |
| --- | --- | --- | --- | --- |
| `POST` | `/api/v1/admin/integrations/core-banking/disbursements` | Bearer | 200 | Submit an authorised disbursement |
| `GET` | `/api/v1/admin/integrations/core-banking/health` | Bearer | 200 | Core banking integration health |
| `POST` | `/api/v1/admin/integrations/core-banking/repayments` | Bearer | 200 | Submit a repayment posting |
| `GET` | `/api/v1/admin/integrations/core-banking/transactions` | Bearer | 200 | List core banking transactions |
| `GET` | `/api/v1/admin/integrations/core-banking/transactions/{id}` | Bearer | 200 | Get a core banking transaction |
| `POST` | `/api/v1/admin/integrations/core-banking/transactions/{id}/enquiry` | Bearer | 200 | Enquire on a core banking transaction |
| `POST` | `/api/v1/admin/integrations/core-banking/transactions/{id}/reconcile` | Bearer | 200 | Reconcile a core banking transaction |
| `POST` | `/api/v1/admin/integrations/core-banking/transactions/{id}/reversal` | Bearer | 200 | Reverse a confirmed repayment |

## Core banking webhooks

| Method | Path | Access | Success | Summary |
| --- | --- | --- | --- | --- |
| `POST` | `/api/v1/webhooks/core-banking/events` | Public | 200 | Accept a core banking event |

## Credit assessment queue

| Method | Path | Access | Success | Summary |
| --- | --- | --- | --- | --- |
| `GET` | `/api/v1/admin/credit-assessments/queue` | Bearer | 200 | List assigned credit assessments |

## Credit assessments

| Method | Path | Access | Success | Summary |
| --- | --- | --- | --- | --- |
| `POST` | `/api/v1/credit-assessments` | Bearer | 200 | Open a credit assessment |
| `GET` | `/api/v1/credit-assessments/{id}` | Bearer | 200 | Get a credit assessment |
| `POST` | `/api/v1/credit-assessments/{id}/bureau-check` | Bearer | 200 | Request a credit bureau check |
| `POST` | `/api/v1/credit-assessments/{id}/comments` | Bearer | 200 | Add an assessment comment |
| `POST` | `/api/v1/credit-assessments/{id}/evaluate` | Bearer | 200 | Evaluate a credit assessment |
| `POST` | `/api/v1/credit-assessments/{id}/recommend` | Bearer | 200 | Submit an analyst recommendation |
| `GET` | `/api/v1/credit-assessments/{id}/report` | Bearer | 200 | Get the credit assessment report |

## Credit scoring policy

| Method | Path | Access | Success | Summary |
| --- | --- | --- | --- | --- |
| `GET` | `/api/v1/admin/credit-scoring/models` | Bearer | 200 | List scoring models |
| `POST` | `/api/v1/admin/credit-scoring/models` | Bearer | 200 | Create a scoring model draft |
| `GET` | `/api/v1/admin/credit-scoring/models/{id}` | Bearer | 200 | Get a scoring model and its versions |
| `PUT` | `/api/v1/admin/credit-scoring/models/{id}` | Bearer | 200 | Update the draft scoring version |
| `DELETE` | `/api/v1/admin/credit-scoring/models/{id}` | Bearer | 200 | Delete a draft scoring model |
| `POST` | `/api/v1/admin/credit-scoring/models/{id}/activate` | Bearer | 200 | Activate the draft scoring version |
| `POST` | `/api/v1/admin/credit-scoring/models/{id}/versions` | Bearer | 200 | Add a draft scoring version |
| `GET` | `/api/v1/admin/credit-scoring/rules` | Bearer | 200 | List assessment rules |
| `POST` | `/api/v1/admin/credit-scoring/rules` | Bearer | 200 | Create an assessment rule |
| `GET` | `/api/v1/admin/credit-scoring/rules/{id}` | Bearer | 200 | Get an assessment rule |
| `PUT` | `/api/v1/admin/credit-scoring/rules/{id}` | Bearer | 200 | Update an assessment rule |
| `DELETE` | `/api/v1/admin/credit-scoring/rules/{id}` | Bearer | 200 | Delete an assessment rule |

## Customer authentication

| Method | Path | Access | Success | Summary |
| --- | --- | --- | --- | --- |
| `POST` | `/api/v1/auth/forgot-password` | Public | 200 | Request a password-reset code without revealing whether the account exists |
| `POST` | `/api/v1/auth/login` | Public | 200 | Sign in a customer and issue a short-lived access token |
| `POST` | `/api/v1/auth/logout` | Bearer | 200 | Revoke the current customer session and its refresh tokens |
| `GET` | `/api/v1/auth/me` | Bearer | 200 | Return the authenticated customer or staff identity |
| `POST` | `/api/v1/auth/refresh-token` | Public | 200 | Rotate a customer refresh token |
| `POST` | `/api/v1/auth/register` | Public | 200 | Register an individual or SME customer |
| `POST` | `/api/v1/auth/reset-password` | Public | 200 | Reset a password with a one-time code and revoke existing sessions |
| `POST` | `/api/v1/auth/verify-otp` | Public | 200 | Verify an email, phone, or password-reset code |

## Customer dashboard

| Method | Path | Access | Success | Summary |
| --- | --- | --- | --- | --- |
| `GET` | `/api/v1/dashboard/customer` | Bearer | 200 | Customer position, next repayment, statement, and application history |
| `GET` | `/api/v1/dashboard/customer/loans` | Bearer | 200 | The customer's own loans |
| `GET` | `/api/v1/dashboard/customer/repayments` | Bearer | 200 | The customer's own repayment history |

## Customers

| Method | Path | Access | Success | Summary |
| --- | --- | --- | --- | --- |
| `GET` | `/api/v1/customers` | Bearer | 200 | Search and look up customers |
| `POST` | `/api/v1/customers` | Bearer | 200 | Create a customer |
| `GET` | `/api/v1/customers/me` | Bearer | 200 | Get the signed-in customer's profile |
| `GET` | `/api/v1/customers/{id}` | Bearer | 200 | Get a customer |
| `PUT` | `/api/v1/customers/{id}` | Bearer | 200 | Update a customer |
| `GET` | `/api/v1/customers/{id}/accounts` | Bearer | 200 | List customer bank accounts |
| `POST` | `/api/v1/customers/{id}/accounts/verify` | Bearer | 200 | Verify a bank account |
| `POST` | `/api/v1/customers/{id}/beneficial-owners` | Bearer | 200 | Add a beneficial owner |
| `POST` | `/api/v1/customers/{id}/directors` | Bearer | 200 | Add a director |
| `GET` | `/api/v1/customers/{id}/documents` | Bearer | 200 | List customer documents |
| `POST` | `/api/v1/customers/{id}/documents` | Bearer | 200 | Upload a customer document |
| `GET` | `/api/v1/customers/{id}/documents/{documentId}` | Bearer | 200 | Download a customer document |
| `POST` | `/api/v1/customers/{id}/individual-profile` | Bearer | 200 | Save an individual profile |
| `GET` | `/api/v1/customers/{id}/kyc` | Bearer | 200 | Get KYC checks and review cases |
| `POST` | `/api/v1/customers/{id}/kyc` | Bearer | 200 | Run KYC and screening |
| `GET` | `/api/v1/customers/{id}/screening` | Bearer | 200 | Get sanctions, PEP, and AML screening results |
| `POST` | `/api/v1/customers/{id}/sme-profile` | Bearer | 200 | Save an SME profile |

## Feature flags

| Method | Path | Access | Success | Summary |
| --- | --- | --- | --- | --- |
| `GET` | `/api/v1/admin/feature-flags` | Bearer | 200 | List feature flags |
| `POST` | `/api/v1/admin/feature-flags` | Bearer | 200 | Create or update a feature flag |
| `DELETE` | `/api/v1/admin/feature-flags/{id}` | Bearer | 200 | Delete a feature flag that is not sensitive |

## Financial reports

| Method | Path | Access | Success | Summary |
| --- | --- | --- | --- | --- |
| `GET` | `/api/v1/reports/branch-performance` | Bearer | 200 | Branch disbursement, collection, and portfolio performance |
| `GET` | `/api/v1/reports/closures` | Bearer | 200 | Loans closed in the reporting period |
| `GET` | `/api/v1/reports/defaults` | Bearer | 200 | Non-performing loans at 90 days past due |
| `GET` | `/api/v1/reports/delinquency` | Bearer | 200 | Delinquency buckets and overdue loans |
| `GET` | `/api/v1/reports/disbursements` | Bearer | 200 | Disbursement report reconciled to general ledger account 1200 |
| `GET` | `/api/v1/reports/fee-income` | Bearer | 200 | Fee income reconciled to general ledger account 4100 |
| `GET` | `/api/v1/reports/interest-income` | Bearer | 200 | Interest income reconciled to general ledger account 4200 |
| `GET` | `/api/v1/reports/loan-portfolio` | Bearer | 200 | Management lending KPIs, portfolio composition, and product and branch performance |
| `GET` | `/api/v1/reports/management-par` | Bearer | 200 | Portfolio at Risk calculated from the reporting book |
| `GET` | `/api/v1/reports/maturity` | Bearer | 200 | Loan maturity report |
| `GET` | `/api/v1/reports/outstanding` | Bearer | 200 | Outstanding loan report |
| `GET` | `/api/v1/reports/product-performance` | Bearer | 200 | Product disbursement, collection, interest, and portfolio performance |
| `GET` | `/api/v1/reports/reconciliation` | Bearer | 200 | Subledger to general ledger reconciliation |
| `GET` | `/api/v1/reports/repayments` | Bearer | 200 | Repayment report reconciled to general ledger account 2100 |
| `GET` | `/api/v1/reports/restructured` | Bearer | 200 | Approved loan restructures in the reporting period |
| `GET` | `/api/v1/reports/write-offs` | Bearer | 200 | Write-off expense, uncollectible cases, and recoveries |

## Guarantors

| Method | Path | Access | Success | Summary |
| --- | --- | --- | --- | --- |
| `POST` | `/api/v1/guarantors` | Bearer | 200 | Register a guarantor |
| `GET` | `/api/v1/guarantors/{id}` | Bearer | 200 | Get a guarantor |
| `POST` | `/api/v1/guarantors/{id}/accept` | Bearer | 200 | Accept or reject a guarantee |
| `POST` | `/api/v1/guarantors/{id}/documents` | Bearer | 200 | Upload a guarantor document |
| `GET` | `/api/v1/guarantors/{id}/documents/{documentId}` | Bearer | 200 | Download a guarantor document |
| `POST` | `/api/v1/guarantors/{id}/verify` | Bearer | 200 | Verify a guarantor |

## Loan accounts

| Method | Path | Access | Success | Summary |
| --- | --- | --- | --- | --- |
| `GET` | `/api/v1/loan-accounts` | Bearer | 200 | List loan accounts visible to the caller |
| `GET` | `/api/v1/loan-accounts/{id}` | Bearer | 200 | Get a loan account and its outstanding balances |
| `GET` | `/api/v1/loan-accounts/{id}/balance` | Bearer | 200 | Get the outstanding balance of a loan account |
| `POST` | `/api/v1/loan-accounts/{id}/interest-adjustments` | Bearer | 200 | Request an interest adjustment. It stays in draft until a different officer approves it |
| `GET` | `/api/v1/loan-accounts/{id}/schedule` | Bearer | 200 | Get a versioned repayment schedule, including its calculation parameters |
| `POST` | `/api/v1/loan-accounts/{id}/schedule` | Bearer | 200 | Restructure the remaining principal onto a new versioned schedule |
| `POST` | `/api/v1/loan-accounts/{id}/settle` | Bearer | 200 | Settle a loan for the quoted amount after core banking confirms the cash |
| `POST` | `/api/v1/loan-accounts/{id}/settlement-quote` | Bearer | 200 | Quote an early or contractual settlement without posting it |
| `GET` | `/api/v1/loan-accounts/{id}/statement` | Bearer | 200 | Get a loan statement, interest breakdown, and transaction history |

## Loan agreement templates

| Method | Path | Access | Success | Summary |
| --- | --- | --- | --- | --- |
| `GET` | `/api/v1/admin/loan-agreement-templates` | Bearer | 200 | List agreement templates |
| `POST` | `/api/v1/admin/loan-agreement-templates` | Bearer | 200 | Create an agreement template |
| `GET` | `/api/v1/admin/loan-agreement-templates/{id}` | Bearer | 200 | Get an agreement template |
| `PUT` | `/api/v1/admin/loan-agreement-templates/{id}` | Bearer | 200 | Publish a new template version |
| `DELETE` | `/api/v1/admin/loan-agreement-templates/{id}` | Bearer | 200 | Delete an unused agreement template |

## Loan application queue

| Method | Path | Access | Success | Summary |
| --- | --- | --- | --- | --- |
| `GET` | `/api/v1/admin/loan-applications/queue` | Bearer | 200 | List the staff application queue |
| `POST` | `/api/v1/admin/loan-applications/{id}/transitions` | Bearer | 200 | Move an application to the next status |

## Loan applications

| Method | Path | Access | Success | Summary |
| --- | --- | --- | --- | --- |
| `GET` | `/api/v1/loan-applications` | Bearer | 200 | List visible loan applications |
| `POST` | `/api/v1/loan-applications` | Bearer | 200 | Start a loan application draft |
| `GET` | `/api/v1/loan-applications/my-applications` | Bearer | 200 | List the signed-in customer's applications |
| `GET` | `/api/v1/loan-applications/summary` | Bearer | 200 | Customer application dashboard |
| `GET` | `/api/v1/loan-applications/{id}` | Bearer | 200 | Get a loan application |
| `PUT` | `/api/v1/loan-applications/{id}` | Bearer | 200 | Update a draft or a returned application |
| `POST` | `/api/v1/loan-applications/{id}/accept-offer` | Bearer | 200 | Accept an issued offer |
| `GET` | `/api/v1/loan-applications/{id}/documents` | Bearer | 200 | List current application documents |
| `POST` | `/api/v1/loan-applications/{id}/documents` | Bearer | 200 | Upload a supporting document |
| `GET` | `/api/v1/loan-applications/{id}/history` | Bearer | 200 | Get application status history and events |
| `POST` | `/api/v1/loan-applications/{id}/submit` | Bearer | 200 | Submit an application |
| `POST` | `/api/v1/loan-applications/{id}/withdraw` | Bearer | 200 | Withdraw an application |

## Loan approvals

| Method | Path | Access | Success | Summary |
| --- | --- | --- | --- | --- |
| `POST` | `/api/v1/approvals` | Bearer | 200 | Request approval |
| `GET` | `/api/v1/approvals/my-queue` | Bearer | 200 | List the caller's approval queue |
| `GET` | `/api/v1/approvals/{id}` | Bearer | 200 | Get an approval |
| `POST` | `/api/v1/approvals/{id}/approve` | Bearer | 200 | Approve the current stage |
| `POST` | `/api/v1/approvals/{id}/delegate` | Bearer | 200 | Delegate this approval |
| `GET` | `/api/v1/approvals/{id}/history` | Bearer | 200 | Get approval history |
| `POST` | `/api/v1/approvals/{id}/reject` | Bearer | 200 | Reject the application |
| `POST` | `/api/v1/approvals/{id}/request-information` | Bearer | 200 | Request additional information |
| `POST` | `/api/v1/approvals/{id}/return` | Bearer | 200 | Return the application for correction |

## Loan offers

| Method | Path | Access | Success | Summary |
| --- | --- | --- | --- | --- |
| `GET` | `/api/v1/customers/me/loan-offers` | Bearer | 200 | List my loan offers |
| `POST` | `/api/v1/loan-applications/{id}/generate-offer` | Bearer | 200 | Generate a loan offer |
| `GET` | `/api/v1/loan-offers/{id}` | Bearer | 200 | Get a loan offer |
| `POST` | `/api/v1/loan-offers/{id}/accept` | Bearer | 200 | Accept a loan offer |
| `GET` | `/api/v1/loan-offers/{id}/agreement` | Bearer | 200 | Get a loan agreement |
| `GET` | `/api/v1/loan-offers/{id}/agreement/document` | Bearer | 200 | Download the agreement PDF |
| `POST` | `/api/v1/loan-offers/{id}/cancel` | Bearer | 200 | Cancel a loan offer |
| `POST` | `/api/v1/loan-offers/{id}/decline` | Bearer | 200 | Decline a loan offer |
| `GET` | `/api/v1/loan-offers/{id}/schedule` | Bearer | 200 | Get the offer repayment schedule |

## Loan products

| Method | Path | Access | Success | Summary |
| --- | --- | --- | --- | --- |
| `POST` | `/api/v1/admin/loan-products` | Bearer | 200 | Create a draft loan product |
| `PUT` | `/api/v1/admin/loan-products/{id}` | Bearer | 200 | Update the open draft |
| `POST` | `/api/v1/admin/loan-products/{id}/activate` | Bearer | 200 | Activate an approved product version |
| `POST` | `/api/v1/admin/loan-products/{id}/approve` | Bearer | 200 | Approve a submitted product version |
| `POST` | `/api/v1/admin/loan-products/{id}/deactivate` | Bearer | 200 | Deactivate a product |
| `POST` | `/api/v1/admin/loan-products/{id}/submit` | Bearer | 200 | Submit a draft for approval |
| `POST` | `/api/v1/admin/loan-products/{id}/suspend` | Bearer | 200 | Suspend an active product |
| `GET` | `/api/v1/admin/loan-products/{id}/versions` | Bearer | 200 | List product version history |
| `POST` | `/api/v1/admin/loan-products/{id}/versions` | Bearer | 200 | Open a new product version |
| `GET` | `/api/v1/loan-products` | Bearer | 200 | List loan products |
| `GET` | `/api/v1/loan-products/{id}` | Bearer | 200 | Get a loan product |
| `GET` | `/api/v1/loan-products/{id}/calculator-config` | Bearer | 200 | Get calculator configuration |

## Loan restructuring

| Method | Path | Access | Success | Summary |
| --- | --- | --- | --- | --- |
| `POST` | `/api/v1/admin/restructuring/{id}/approve` | Bearer | 200 | Approve a restructuring. The previous schedule is kept, accrued amounts stay, and core cash is reconciled |
| `POST` | `/api/v1/admin/restructuring/{id}/reject` | Bearer | 200 | Reject a restructuring without changing the repayment schedule or accrued amounts |
| `POST` | `/api/v1/loan-accounts/{id}/restructure` | Bearer | 200 | Request a loan restructuring. It stays pending until a different authorised officer approves it |
| `GET` | `/api/v1/loan-accounts/{id}/restructuring-history` | Bearer | 200 | List restructuring requests and the delinquency position retained with each decision |

## Loan security

| Method | Path | Access | Success | Summary |
| --- | --- | --- | --- | --- |
| `GET` | `/api/v1/loan-applications/{applicationId}/collaterals` | Bearer | 200 | List collateral allocated to an application |
| `POST` | `/api/v1/loan-applications/{applicationId}/collaterals` | Bearer | 200 | Allocate collateral to an application |
| `GET` | `/api/v1/loan-applications/{applicationId}/guarantors` | Bearer | 200 | List guarantors linked to an application |
| `POST` | `/api/v1/loan-applications/{applicationId}/guarantors` | Bearer | 200 | Link a guarantor to an application |

## Notification preferences

| Method | Path | Access | Success | Summary |
| --- | --- | --- | --- | --- |
| `GET` | `/api/v1/customers/me/notification-preferences` | Bearer | 200 | List my notification preferences |
| `PUT` | `/api/v1/customers/me/notification-preferences` | Bearer | 200 | Replace my notification preferences |

## Notification templates

| Method | Path | Access | Success | Summary |
| --- | --- | --- | --- | --- |
| `GET` | `/api/v1/admin/notification-templates` | Bearer | 200 | List notification templates |
| `POST` | `/api/v1/admin/notification-templates` | Bearer | 200 | Create a notification template |
| `GET` | `/api/v1/admin/notification-templates/{id}` | Bearer | 200 | Get a notification template |
| `PUT` | `/api/v1/admin/notification-templates/{id}` | Bearer | 200 | Revise a notification template. The previous version is kept and deactivated. |
| `DELETE` | `/api/v1/admin/notification-templates/{id}` | Bearer | 200 | Retire a notification template |

## Notifications

| Method | Path | Access | Success | Summary |
| --- | --- | --- | --- | --- |
| `GET` | `/api/v1/notifications` | Bearer | 200 | List notifications for the caller |
| `GET` | `/api/v1/notifications/{id}` | Bearer | 200 | Get one notification |
| `POST` | `/api/v1/notifications/{id}/read` | Bearer | 200 | Mark an in-app notification as read |

## Operations

| Method | Path | Access | Success | Summary |
| --- | --- | --- | --- | --- |
| `GET` | `/api/v1/admin/operations/health` | Bearer | 200 | Operational health dashboard |

## Permissions

| Method | Path | Access | Success | Summary |
| --- | --- | --- | --- | --- |
| `GET` | `/api/v1/admin/permissions` | Bearer | 200 | List permissions |
| `POST` | `/api/v1/admin/permissions` | Bearer | 200 | Create a permission |
| `GET` | `/api/v1/admin/permissions/{id}` | Bearer | 200 | Get a permission |
| `PUT` | `/api/v1/admin/permissions/{id}` | Bearer | 200 | Update a permission description |
| `DELETE` | `/api/v1/admin/permissions/{id}` | Bearer | 200 | Delete a custom permission |

## Repayments

| Method | Path | Access | Success | Summary |
| --- | --- | --- | --- | --- |
| `POST` | `/api/v1/repayments` | Bearer | 200 | Collect a repayment. The loan balance changes only after core banking confirms the cash |
| `GET` | `/api/v1/repayments/{id}` | Bearer | 200 | Get a repayment receipt and its allocation |
| `POST` | `/api/v1/repayments/{id}/reversal` | Bearer | 200 | Reverse a posted repayment after core banking confirms the reversal |

## Report exports

| Method | Path | Access | Success | Summary |
| --- | --- | --- | --- | --- |
| `POST` | `/api/v1/reports/exports` | Bearer | 200, 202 | Request a CSV, Excel, or PDF export. Large extracts are generated asynchronously. |
| `GET` | `/api/v1/reports/exports/{id}` | Bearer | 200 | Export status for the user who requested it |
| `GET` | `/api/v1/reports/exports/{id}/download` | Bearer | 200 | Download a ready export with the one-time token |

## Roles

| Method | Path | Access | Success | Summary |
| --- | --- | --- | --- | --- |
| `GET` | `/api/v1/admin/roles` | Bearer | 200 | List roles |
| `POST` | `/api/v1/admin/roles` | Bearer | 200 | Create a role |
| `GET` | `/api/v1/admin/roles/{id}` | Bearer | 200 | Get a role |
| `PUT` | `/api/v1/admin/roles/{id}` | Bearer | 200 | Update a role |
| `DELETE` | `/api/v1/admin/roles/{id}` | Bearer | 200 | Delete a custom role |

## Staff authentication

| Method | Path | Access | Success | Summary |
| --- | --- | --- | --- | --- |
| `POST` | `/api/v1/admin/auth/login` | Public | 200 | Sign in a staff account |
| `POST` | `/api/v1/admin/auth/logout` | Bearer | 200 | Revoke the current staff session and its refresh tokens |
| `POST` | `/api/v1/admin/auth/mfa/verify` | Public | 200 | Complete a staff MFA challenge |
| `POST` | `/api/v1/admin/auth/refresh-token` | Public | 200 | Rotate a staff refresh token |

## Staff dashboards

| Method | Path | Access | Success | Summary |
| --- | --- | --- | --- | --- |
| `GET` | `/api/v1/dashboard/branch` | Bearer | 200 | Branch portfolio and application performance within the caller's branch assignment |
| `GET` | `/api/v1/dashboard/collections` | Bearer | 200 | Overdue portfolio, PAR, collection rate, open cases, and recoveries |
| `GET` | `/api/v1/dashboard/credit` | Bearer | 200 | Approval and rejection rates, turnaround, average loan size, and credit risk distribution |
| `GET` | `/api/v1/dashboard/operations` | Bearer | 200 | Application pipeline, disbursed loans, outstanding and overdue portfolio, and collection performance |

## System

| Method | Path | Access | Success | Summary |
| --- | --- | --- | --- | --- |
| `GET` | `/api/v1/system/version` | Public | 200 | Get the running application version |

## System configuration

| Method | Path | Access | Success | Summary |
| --- | --- | --- | --- | --- |
| `GET` | `/api/v1/admin/system-configurations` | Bearer | 200 | List system configuration. Sensitive values are masked. |
| `POST` | `/api/v1/admin/system-configurations` | Bearer | 200 | Create or replace a system configuration. Sensitive changes return pending approval. |
| `GET` | `/api/v1/admin/system-configurations/changes` | Bearer | 200 | List configuration change requests |
| `POST` | `/api/v1/admin/system-configurations/changes/{id}/approve` | Bearer | 200 | Approve a configuration change. The requester cannot approve it. |
| `POST` | `/api/v1/admin/system-configurations/changes/{id}/reject` | Bearer | 200 | Reject a configuration change |
| `GET` | `/api/v1/admin/system-configurations/{id}` | Bearer | 200 | Get one system configuration. Sensitive values are masked. |
| `PUT` | `/api/v1/admin/system-configurations/{id}` | Bearer | 200 | Update a system configuration. The path id selects the current row; the key in the body is the setting being changed. |
| `DELETE` | `/api/v1/admin/system-configurations/{id}` | Bearer | 200 | Delete a system configuration. Sensitive deletes wait for approval. |

## User administration

| Method | Path | Access | Success | Summary |
| --- | --- | --- | --- | --- |
| `GET` | `/api/v1/admin/users` | Bearer | 200 | List accounts visible to the caller |
| `POST` | `/api/v1/admin/users` | Bearer | 200 | Create a staff account |
| `GET` | `/api/v1/admin/users/{id}` | Bearer | 200 | Get an account when the caller is allowed to see it |
| `PUT` | `/api/v1/admin/users/{id}` | Bearer | 200 | Update an account, including activation and deactivation |
| `DELETE` | `/api/v1/admin/users/{id}` | Bearer | 200 | Disable an account and revoke its sessions |
| `POST` | `/api/v1/admin/users/{id}/revoke-sessions` | Bearer | 200 | Revoke every active session for an account |
| `POST` | `/api/v1/admin/users/{id}/roles` | Bearer | 200 | Replace the roles assigned to an account |
| `GET` | `/api/v1/admin/users/{id}/sessions` | Bearer | 200 | List recent sessions for an account |

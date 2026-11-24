# Request and response schema catalogue

Source: `docs/api/openapi.json`. Names are the springdoc schema names.
A monetary property is a JSON number in this contract. A currency property is a separate string.
Success envelopes are `ApiResponse*`. Failures use `ApiError`.

## Envelope shapes

| Shape | Properties |
| --- | --- |
| Success | `success`, `code`, `message`, `data`, `correlationId`, `timestamp` |
| Error | `success`, `code`, `message`, `errors`, `correlationId`, `timestamp`, `path` |
| PageResponse | `items`, `page`, `size`, `total` |
| CustomerLoanPage | `loans`, `page`, `size`, `total` |
| CustomerRepaymentPage | `repayments`, `page`, `size`, `total`, and period fields |
| DetailPage | `lines`, `page`, `size`, `total` |
| AuditPage | `items`, `page`, `size`, `total`, `chainIntact` |

`page` is zero-based. `size` is from 1 to 100 on endpoints that use `PageBounds`.

## Schemas

### `AcceptOfferRequest`

Records that the customer read this agreement version and that their KYC identity is verified. agreementRead must be true. This request is an electronic acceptance, not a qualified digital signature.

| Property | Type | Required |
| --- | --- | --- |
| `version` | integer (int32) | yes |
| `identityVerificationReference` | string | yes |
| `agreementRead` | boolean | yes |
| `expectedChecksum` | string | no |

### `AcceptanceResponse`

| Property | Type | Required |
| --- | --- | --- |
| `id` | string (uuid) | no |
| `acceptedAt` | string (date-time) | no |
| `identityVerificationReference` | string | no |
| `kycStatus` | string | no |
| `signatureProvider` | string | no |
| `signatureAssurance` | string | no |
| `signatureReference` | string | no |
| `disclaimer` | string | no |

### `AccountResponse`

| Property | Type | Required |
| --- | --- | --- |
| `id` | string (uuid) | no |
| `bankCode` | string | no |
| `bankName` | string | no |
| `accountNumberMasked` | string | no |
| `accountName` | string | no |
| `currencyCode` | string | no |
| `verificationStatus` | string | no |
| `verifiedAt` | string (date-time) | no |

### `AccountVerifyRequest`

| Property | Type | Required |
| --- | --- | --- |
| `bankCode` | string | yes |
| `accountNumber` | string | yes |

### `ActivityRequest`

| Property | Type | Required |
| --- | --- | --- |
| `activityType` | string | no |
| `note` | string | yes |
| `assigneeId` | string (uuid) | no |

### `ActivityResponse`

| Property | Type | Required |
| --- | --- | --- |
| `id` | string (uuid) | no |
| `activityType` | string | no |
| `note` | string | no |
| `actorName` | string | no |
| `createdAt` | string (date-time) | no |

### `AddressRequest`

| Property | Type | Required |
| --- | --- | --- |
| `line1` | string | yes |
| `line2` | string | no |
| `city` | string | yes |
| `state` | string | yes |
| `countryCode` | string | no |
| `postalCode` | string | no |

### `AddressResponse`

| Property | Type | Required |
| --- | --- | --- |
| `id` | string (uuid) | no |
| `addressType` | string | no |
| `line1` | string | no |
| `line2` | string | no |
| `city` | string | no |
| `state` | string | no |
| `countryCode` | string | no |
| `postalCode` | string | no |

### `AgreementResponse`

| Property | Type | Required |
| --- | --- | --- |
| `id` | string (uuid) | no |
| `offerId` | string (uuid) | no |
| `offerVersion` | integer (int32) | no |
| `kind` | string | no |
| `templateId` | string (uuid) | no |
| `templateVersion` | integer (int32) | no |
| `checksumSha256` | string | no |
| `sizeBytes` | integer (int64) | no |
| `contentType` | string | no |
| `fileName` | string | no |
| `frozenAt` | string (date-time) | no |
| `supersededAt` | string (date-time) | no |
| `renderedText` | string | no |
| `integrity` | string | no |
| `acceptance` | `AcceptanceResponse` | no |
| `related` | array of `RelatedAgreement` | no |
| `signatureDisclaimer` | string | no |

### `AgreementTemplateResponse`

| Property | Type | Required |
| --- | --- | --- |
| `id` | string (uuid) | no |
| `code` | string | no |
| `name` | string | no |
| `kind` | string | no |
| `versionNumber` | integer (int32) | no |
| `body` | string | no |
| `active` | boolean | no |
| `checksumSha256` | string | no |
| `createdAt` | string (date-time) | no |

### `AlertResponse`

| Property | Type | Required |
| --- | --- | --- |
| `id` | string (uuid) | no |
| `customerId` | string (uuid) | no |
| `alertType` | string | no |
| `severity` | string | no |
| `status` | string | no |
| `summary` | string | no |
| `openedAt` | string (date-time) | no |

### `AllocateCollateralRequest`

| Property | Type | Required |
| --- | --- | --- |
| `collateralId` | string (uuid) | yes |
| `allocatedAmount` | number | yes |
| `sharedSecurity` | boolean | yes |

### `AllocationResponse`

| Property | Type | Required |
| --- | --- | --- |
| `component` | string | no |
| `amount` | number | no |
| `scheduleItemId` | string (uuid) | no |

### `AllocationSummaryResponse`

| Property | Type | Required |
| --- | --- | --- |
| `id` | string (uuid) | no |
| `applicationId` | string (uuid) | no |
| `allocatedAmount` | number | no |
| `sharedSecurity` | boolean | no |
| `status` | string | no |
| `allocatedAt` | string (date-time) | no |
| `releasedAt` | string (date-time) | no |

### `ApiError`

Error envelope. Success responses use a different shape: success, code, message, data, correlationId, and timestamp.

| Property | Type | Required |
| --- | --- | --- |
| `success` | boolean | yes |
| `code` | string | yes |
| `message` | string | yes |
| `errors` | array of object | yes |
| `correlationId` | string | yes |
| `timestamp` | string (date-time) | yes |
| `path` | string | yes |

### `ApiResponseAccountResponse`

| Property | Type | Required |
| --- | --- | --- |
| `success` | boolean | no |
| `code` | string | no |
| `message` | string | no |
| `data` | `AccountResponse` | no |
| `correlationId` | string | no |
| `timestamp` | string (date-time) | no |

### `ApiResponseActivityResponse`

| Property | Type | Required |
| --- | --- | --- |
| `success` | boolean | no |
| `code` | string | no |
| `message` | string | no |
| `data` | `ActivityResponse` | no |
| `correlationId` | string | no |
| `timestamp` | string (date-time) | no |

### `ApiResponseAgreementResponse`

| Property | Type | Required |
| --- | --- | --- |
| `success` | boolean | no |
| `code` | string | no |
| `message` | string | no |
| `data` | `AgreementResponse` | no |
| `correlationId` | string | no |
| `timestamp` | string (date-time) | no |

### `ApiResponseAgreementTemplateResponse`

| Property | Type | Required |
| --- | --- | --- |
| `success` | boolean | no |
| `code` | string | no |
| `message` | string | no |
| `data` | `AgreementTemplateResponse` | no |
| `correlationId` | string | no |
| `timestamp` | string (date-time) | no |

### `ApiResponseApplicationDashboardResponse`

| Property | Type | Required |
| --- | --- | --- |
| `success` | boolean | no |
| `code` | string | no |
| `message` | string | no |
| `data` | `ApplicationDashboardResponse` | no |
| `correlationId` | string | no |
| `timestamp` | string (date-time) | no |

### `ApiResponseApplicationDocumentResponse`

| Property | Type | Required |
| --- | --- | --- |
| `success` | boolean | no |
| `code` | string | no |
| `message` | string | no |
| `data` | `ApplicationDocumentResponse` | no |
| `correlationId` | string | no |
| `timestamp` | string (date-time) | no |

### `ApiResponseApplicationHistoryResponse`

| Property | Type | Required |
| --- | --- | --- |
| `success` | boolean | no |
| `code` | string | no |
| `message` | string | no |
| `data` | `ApplicationHistoryResponse` | no |
| `correlationId` | string | no |
| `timestamp` | string (date-time) | no |

### `ApiResponseApprovalMatrixResponse`

| Property | Type | Required |
| --- | --- | --- |
| `success` | boolean | no |
| `code` | string | no |
| `message` | string | no |
| `data` | `ApprovalMatrixResponse` | no |
| `correlationId` | string | no |
| `timestamp` | string (date-time) | no |

### `ApiResponseApprovalResponse`

| Property | Type | Required |
| --- | --- | --- |
| `success` | boolean | no |
| `code` | string | no |
| `message` | string | no |
| `data` | `ApprovalResponse` | no |
| `correlationId` | string | no |
| `timestamp` | string (date-time) | no |

### `ApiResponseAuditLogResponse`

| Property | Type | Required |
| --- | --- | --- |
| `success` | boolean | no |
| `code` | string | no |
| `message` | string | no |
| `data` | `AuditLogResponse` | no |
| `correlationId` | string | no |
| `timestamp` | string (date-time) | no |

### `ApiResponseAuditPage`

| Property | Type | Required |
| --- | --- | --- |
| `success` | boolean | no |
| `code` | string | no |
| `message` | string | no |
| `data` | `AuditPage` | no |
| `correlationId` | string | no |
| `timestamp` | string (date-time) | no |

### `ApiResponseAuthTokenResponse`

| Property | Type | Required |
| --- | --- | --- |
| `success` | boolean | no |
| `code` | string | no |
| `message` | string | no |
| `data` | `AuthTokenResponse` | no |
| `correlationId` | string | no |
| `timestamp` | string (date-time) | no |

### `ApiResponseBalanceResponse`

| Property | Type | Required |
| --- | --- | --- |
| `success` | boolean | no |
| `code` | string | no |
| `message` | string | no |
| `data` | `BalanceResponse` | no |
| `correlationId` | string | no |
| `timestamp` | string (date-time) | no |

### `ApiResponseBranch`

| Property | Type | Required |
| --- | --- | --- |
| `success` | boolean | no |
| `code` | string | no |
| `message` | string | no |
| `data` | `Branch` | no |
| `correlationId` | string | no |
| `timestamp` | string (date-time) | no |

### `ApiResponseBranchDashboard`

| Property | Type | Required |
| --- | --- | --- |
| `success` | boolean | no |
| `code` | string | no |
| `message` | string | no |
| `data` | `BranchDashboard` | no |
| `correlationId` | string | no |
| `timestamp` | string (date-time) | no |

### `ApiResponseBureauCheckResponse`

| Property | Type | Required |
| --- | --- | --- |
| `success` | boolean | no |
| `code` | string | no |
| `message` | string | no |
| `data` | `BureauCheckResponse` | no |
| `correlationId` | string | no |
| `timestamp` | string (date-time) | no |

### `ApiResponseBusinessCalendar`

| Property | Type | Required |
| --- | --- | --- |
| `success` | boolean | no |
| `code` | string | no |
| `message` | string | no |
| `data` | `BusinessCalendar` | no |
| `correlationId` | string | no |
| `timestamp` | string (date-time) | no |

### `ApiResponseCalculatorConfigResponse`

| Property | Type | Required |
| --- | --- | --- |
| `success` | boolean | no |
| `code` | string | no |
| `message` | string | no |
| `data` | `CalculatorConfigResponse` | no |
| `correlationId` | string | no |
| `timestamp` | string (date-time) | no |

### `ApiResponseCalendarDetail`

| Property | Type | Required |
| --- | --- | --- |
| `success` | boolean | no |
| `code` | string | no |
| `message` | string | no |
| `data` | `CalendarDetail` | no |
| `correlationId` | string | no |
| `timestamp` | string (date-time) | no |

### `ApiResponseCaseDetailResponse`

| Property | Type | Required |
| --- | --- | --- |
| `success` | boolean | no |
| `code` | string | no |
| `message` | string | no |
| `data` | `CaseDetailResponse` | no |
| `correlationId` | string | no |
| `timestamp` | string (date-time) | no |

### `ApiResponseCaseResponse`

| Property | Type | Required |
| --- | --- | --- |
| `success` | boolean | no |
| `code` | string | no |
| `message` | string | no |
| `data` | `CaseResponse` | no |
| `correlationId` | string | no |
| `timestamp` | string (date-time) | no |

### `ApiResponseChangeRequest`

| Property | Type | Required |
| --- | --- | --- |
| `success` | boolean | no |
| `code` | string | no |
| `message` | string | no |
| `data` | `ChangeRequest` | no |
| `correlationId` | string | no |
| `timestamp` | string (date-time) | no |

### `ApiResponseCollateralDetailResponse`

| Property | Type | Required |
| --- | --- | --- |
| `success` | boolean | no |
| `code` | string | no |
| `message` | string | no |
| `data` | `CollateralDetailResponse` | no |
| `correlationId` | string | no |
| `timestamp` | string (date-time) | no |

### `ApiResponseCollectionsDashboard`

| Property | Type | Required |
| --- | --- | --- |
| `success` | boolean | no |
| `code` | string | no |
| `message` | string | no |
| `data` | `CollectionsDashboard` | no |
| `correlationId` | string | no |
| `timestamp` | string (date-time) | no |

### `ApiResponseCollectionsReportResponse`

| Property | Type | Required |
| --- | --- | --- |
| `success` | boolean | no |
| `code` | string | no |
| `message` | string | no |
| `data` | `CollectionsReportResponse` | no |
| `correlationId` | string | no |
| `timestamp` | string (date-time) | no |

### `ApiResponseCommentResponse`

| Property | Type | Required |
| --- | --- | --- |
| `success` | boolean | no |
| `code` | string | no |
| `message` | string | no |
| `data` | `CommentResponse` | no |
| `correlationId` | string | no |
| `timestamp` | string (date-time) | no |

### `ApiResponseComplaintDetailResponse`

| Property | Type | Required |
| --- | --- | --- |
| `success` | boolean | no |
| `code` | string | no |
| `message` | string | no |
| `data` | `ComplaintDetailResponse` | no |
| `correlationId` | string | no |
| `timestamp` | string (date-time) | no |

### `ApiResponseComplaintResponse`

| Property | Type | Required |
| --- | --- | --- |
| `success` | boolean | no |
| `code` | string | no |
| `message` | string | no |
| `data` | `ComplaintResponse` | no |
| `correlationId` | string | no |
| `timestamp` | string (date-time) | no |

### `ApiResponseConfigWrite`

| Property | Type | Required |
| --- | --- | --- |
| `success` | boolean | no |
| `code` | string | no |
| `message` | string | no |
| `data` | `ConfigWrite` | no |
| `correlationId` | string | no |
| `timestamp` | string (date-time) | no |

### `ApiResponseCoreBankingHealthResponse`

| Property | Type | Required |
| --- | --- | --- |
| `success` | boolean | no |
| `code` | string | no |
| `message` | string | no |
| `data` | `CoreBankingHealthResponse` | no |
| `correlationId` | string | no |
| `timestamp` | string (date-time) | no |

### `ApiResponseCoreTransactionResponse`

| Property | Type | Required |
| --- | --- | --- |
| `success` | boolean | no |
| `code` | string | no |
| `message` | string | no |
| `data` | `CoreTransactionResponse` | no |
| `correlationId` | string | no |
| `timestamp` | string (date-time) | no |

### `ApiResponseCreateUserResponse`

| Property | Type | Required |
| --- | --- | --- |
| `success` | boolean | no |
| `code` | string | no |
| `message` | string | no |
| `data` | `CreateUserResponse` | no |
| `correlationId` | string | no |
| `timestamp` | string (date-time) | no |

### `ApiResponseCreditAssessmentResponse`

| Property | Type | Required |
| --- | --- | --- |
| `success` | boolean | no |
| `code` | string | no |
| `message` | string | no |
| `data` | `CreditAssessmentResponse` | no |
| `correlationId` | string | no |
| `timestamp` | string (date-time) | no |

### `ApiResponseCreditDashboard`

| Property | Type | Required |
| --- | --- | --- |
| `success` | boolean | no |
| `code` | string | no |
| `message` | string | no |
| `data` | `CreditDashboard` | no |
| `correlationId` | string | no |
| `timestamp` | string (date-time) | no |

### `ApiResponseCreditReportResponse`

| Property | Type | Required |
| --- | --- | --- |
| `success` | boolean | no |
| `code` | string | no |
| `message` | string | no |
| `data` | `CreditReportResponse` | no |
| `correlationId` | string | no |
| `timestamp` | string (date-time) | no |

### `ApiResponseCreditRuleResponse`

| Property | Type | Required |
| --- | --- | --- |
| `success` | boolean | no |
| `code` | string | no |
| `message` | string | no |
| `data` | `CreditRuleResponse` | no |
| `correlationId` | string | no |
| `timestamp` | string (date-time) | no |

### `ApiResponseCustomerDashboard`

| Property | Type | Required |
| --- | --- | --- |
| `success` | boolean | no |
| `code` | string | no |
| `message` | string | no |
| `data` | `CustomerDashboard` | no |
| `correlationId` | string | no |
| `timestamp` | string (date-time) | no |

### `ApiResponseCustomerLoanPage`

| Property | Type | Required |
| --- | --- | --- |
| `success` | boolean | no |
| `code` | string | no |
| `message` | string | no |
| `data` | `CustomerLoanPage` | no |
| `correlationId` | string | no |
| `timestamp` | string (date-time) | no |

### `ApiResponseCustomerRepaymentPage`

| Property | Type | Required |
| --- | --- | --- |
| `success` | boolean | no |
| `code` | string | no |
| `message` | string | no |
| `data` | `CustomerRepaymentPage` | no |
| `correlationId` | string | no |
| `timestamp` | string (date-time) | no |

### `ApiResponseCustomerResponse`

| Property | Type | Required |
| --- | --- | --- |
| `success` | boolean | no |
| `code` | string | no |
| `message` | string | no |
| `data` | `CustomerResponse` | no |
| `correlationId` | string | no |
| `timestamp` | string (date-time) | no |

### `ApiResponseDefaultReport`

| Property | Type | Required |
| --- | --- | --- |
| `success` | boolean | no |
| `code` | string | no |
| `message` | string | no |
| `data` | `DefaultReport` | no |
| `correlationId` | string | no |
| `timestamp` | string (date-time) | no |

### `ApiResponseDelegationResponse`

| Property | Type | Required |
| --- | --- | --- |
| `success` | boolean | no |
| `code` | string | no |
| `message` | string | no |
| `data` | `DelegationResponse` | no |
| `correlationId` | string | no |
| `timestamp` | string (date-time) | no |

### `ApiResponseDelinquencyReport`

| Property | Type | Required |
| --- | --- | --- |
| `success` | boolean | no |
| `code` | string | no |
| `message` | string | no |
| `data` | `DelinquencyReport` | no |
| `correlationId` | string | no |
| `timestamp` | string (date-time) | no |

### `ApiResponseDocumentResponse`

| Property | Type | Required |
| --- | --- | --- |
| `success` | boolean | no |
| `code` | string | no |
| `message` | string | no |
| `data` | `DocumentResponse` | no |
| `correlationId` | string | no |
| `timestamp` | string (date-time) | no |

### `ApiResponseEscalationResponse`

| Property | Type | Required |
| --- | --- | --- |
| `success` | boolean | no |
| `code` | string | no |
| `message` | string | no |
| `data` | `EscalationResponse` | no |
| `correlationId` | string | no |
| `timestamp` | string (date-time) | no |

### `ApiResponseExportResponse`

| Property | Type | Required |
| --- | --- | --- |
| `success` | boolean | no |
| `code` | string | no |
| `message` | string | no |
| `data` | `ExportResponse` | no |
| `correlationId` | string | no |
| `timestamp` | string (date-time) | no |

### `ApiResponseGroupReport`

| Property | Type | Required |
| --- | --- | --- |
| `success` | boolean | no |
| `code` | string | no |
| `message` | string | no |
| `data` | `GroupReport` | no |
| `correlationId` | string | no |
| `timestamp` | string (date-time) | no |

### `ApiResponseGuarantorResponse`

| Property | Type | Required |
| --- | --- | --- |
| `success` | boolean | no |
| `code` | string | no |
| `message` | string | no |
| `data` | `GuarantorResponse` | no |
| `correlationId` | string | no |
| `timestamp` | string (date-time) | no |

### `ApiResponseIncomeReport`

| Property | Type | Required |
| --- | --- | --- |
| `success` | boolean | no |
| `code` | string | no |
| `message` | string | no |
| `data` | `IncomeReport` | no |
| `correlationId` | string | no |
| `timestamp` | string (date-time) | no |

### `ApiResponseJournalResponse`

| Property | Type | Required |
| --- | --- | --- |
| `success` | boolean | no |
| `code` | string | no |
| `message` | string | no |
| `data` | `JournalResponse` | no |
| `correlationId` | string | no |
| `timestamp` | string (date-time) | no |

### `ApiResponseKycResponse`

| Property | Type | Required |
| --- | --- | --- |
| `success` | boolean | no |
| `code` | string | no |
| `message` | string | no |
| `data` | `KycResponse` | no |
| `correlationId` | string | no |
| `timestamp` | string (date-time) | no |

### `ApiResponseListAccountResponse`

| Property | Type | Required |
| --- | --- | --- |
| `success` | boolean | no |
| `code` | string | no |
| `message` | string | no |
| `data` | array of `AccountResponse` | no |
| `correlationId` | string | no |
| `timestamp` | string (date-time) | no |

### `ApiResponseListAgreementTemplateResponse`

| Property | Type | Required |
| --- | --- | --- |
| `success` | boolean | no |
| `code` | string | no |
| `message` | string | no |
| `data` | array of `AgreementTemplateResponse` | no |
| `correlationId` | string | no |
| `timestamp` | string (date-time) | no |

### `ApiResponseListApplicationDocumentResponse`

| Property | Type | Required |
| --- | --- | --- |
| `success` | boolean | no |
| `code` | string | no |
| `message` | string | no |
| `data` | array of `ApplicationDocumentResponse` | no |
| `correlationId` | string | no |
| `timestamp` | string (date-time) | no |

### `ApiResponseListApprovalHistoryResponse`

| Property | Type | Required |
| --- | --- | --- |
| `success` | boolean | no |
| `code` | string | no |
| `message` | string | no |
| `data` | array of `ApprovalHistoryResponse` | no |
| `correlationId` | string | no |
| `timestamp` | string (date-time) | no |

### `ApiResponseListApprovalMatrixResponse`

| Property | Type | Required |
| --- | --- | --- |
| `success` | boolean | no |
| `code` | string | no |
| `message` | string | no |
| `data` | array of `ApprovalMatrixResponse` | no |
| `correlationId` | string | no |
| `timestamp` | string (date-time) | no |

### `ApiResponseListBusinessCalendar`

| Property | Type | Required |
| --- | --- | --- |
| `success` | boolean | no |
| `code` | string | no |
| `message` | string | no |
| `data` | array of `BusinessCalendar` | no |
| `correlationId` | string | no |
| `timestamp` | string (date-time) | no |

### `ApiResponseListCreditRuleResponse`

| Property | Type | Required |
| --- | --- | --- |
| `success` | boolean | no |
| `code` | string | no |
| `message` | string | no |
| `data` | array of `CreditRuleResponse` | no |
| `correlationId` | string | no |
| `timestamp` | string (date-time) | no |

### `ApiResponseListDelegationResponse`

| Property | Type | Required |
| --- | --- | --- |
| `success` | boolean | no |
| `code` | string | no |
| `message` | string | no |
| `data` | array of `DelegationResponse` | no |
| `correlationId` | string | no |
| `timestamp` | string (date-time) | no |

### `ApiResponseListDocumentResponse`

| Property | Type | Required |
| --- | --- | --- |
| `success` | boolean | no |
| `code` | string | no |
| `message` | string | no |
| `data` | array of `DocumentResponse` | no |
| `correlationId` | string | no |
| `timestamp` | string (date-time) | no |

### `ApiResponseListFeatureFlag`

| Property | Type | Required |
| --- | --- | --- |
| `success` | boolean | no |
| `code` | string | no |
| `message` | string | no |
| `data` | array of `FeatureFlag` | no |
| `correlationId` | string | no |
| `timestamp` | string (date-time) | no |

### `ApiResponseListLoanGuarantorResponse`

| Property | Type | Required |
| --- | --- | --- |
| `success` | boolean | no |
| `code` | string | no |
| `message` | string | no |
| `data` | array of `LoanGuarantorResponse` | no |
| `correlationId` | string | no |
| `timestamp` | string (date-time) | no |

### `ApiResponseListLoanProductVersionResponse`

| Property | Type | Required |
| --- | --- | --- |
| `success` | boolean | no |
| `code` | string | no |
| `message` | string | no |
| `data` | array of `LoanProductVersionResponse` | no |
| `correlationId` | string | no |
| `timestamp` | string (date-time) | no |

### `ApiResponseListPermissionResponse`

| Property | Type | Required |
| --- | --- | --- |
| `success` | boolean | no |
| `code` | string | no |
| `message` | string | no |
| `data` | array of `PermissionResponse` | no |
| `correlationId` | string | no |
| `timestamp` | string (date-time) | no |

### `ApiResponseListPreferenceResponse`

| Property | Type | Required |
| --- | --- | --- |
| `success` | boolean | no |
| `code` | string | no |
| `message` | string | no |
| `data` | array of `PreferenceResponse` | no |
| `correlationId` | string | no |
| `timestamp` | string (date-time) | no |

### `ApiResponseListRestructuringResponse`

| Property | Type | Required |
| --- | --- | --- |
| `success` | boolean | no |
| `code` | string | no |
| `message` | string | no |
| `data` | array of `RestructuringResponse` | no |
| `correlationId` | string | no |
| `timestamp` | string (date-time) | no |

### `ApiResponseListRoleResponse`

| Property | Type | Required |
| --- | --- | --- |
| `success` | boolean | no |
| `code` | string | no |
| `message` | string | no |
| `data` | array of `RoleResponse` | no |
| `correlationId` | string | no |
| `timestamp` | string (date-time) | no |

### `ApiResponseListScheduleItemResponse`

| Property | Type | Required |
| --- | --- | --- |
| `success` | boolean | no |
| `code` | string | no |
| `message` | string | no |
| `data` | array of `ScheduleItemResponse` | no |
| `correlationId` | string | no |
| `timestamp` | string (date-time) | no |

### `ApiResponseListScoringModelResponse`

| Property | Type | Required |
| --- | --- | --- |
| `success` | boolean | no |
| `code` | string | no |
| `message` | string | no |
| `data` | array of `ScoringModelResponse` | no |
| `correlationId` | string | no |
| `timestamp` | string (date-time) | no |

### `ApiResponseListSessionResponse`

| Property | Type | Required |
| --- | --- | --- |
| `success` | boolean | no |
| `code` | string | no |
| `message` | string | no |
| `data` | array of `SessionResponse` | no |
| `correlationId` | string | no |
| `timestamp` | string (date-time) | no |

### `ApiResponseLoanAccountResponse`

| Property | Type | Required |
| --- | --- | --- |
| `success` | boolean | no |
| `code` | string | no |
| `message` | string | no |
| `data` | `LoanAccountResponse` | no |
| `correlationId` | string | no |
| `timestamp` | string (date-time) | no |

### `ApiResponseLoanApplicationResponse`

| Property | Type | Required |
| --- | --- | --- |
| `success` | boolean | no |
| `code` | string | no |
| `message` | string | no |
| `data` | `LoanApplicationResponse` | no |
| `correlationId` | string | no |
| `timestamp` | string (date-time) | no |

### `ApiResponseLoanCollateralListResponse`

| Property | Type | Required |
| --- | --- | --- |
| `success` | boolean | no |
| `code` | string | no |
| `message` | string | no |
| `data` | `LoanCollateralListResponse` | no |
| `correlationId` | string | no |
| `timestamp` | string (date-time) | no |

### `ApiResponseLoanCollateralResponse`

| Property | Type | Required |
| --- | --- | --- |
| `success` | boolean | no |
| `code` | string | no |
| `message` | string | no |
| `data` | `LoanCollateralResponse` | no |
| `correlationId` | string | no |
| `timestamp` | string (date-time) | no |

### `ApiResponseLoanGuarantorResponse`

| Property | Type | Required |
| --- | --- | --- |
| `success` | boolean | no |
| `code` | string | no |
| `message` | string | no |
| `data` | `LoanGuarantorResponse` | no |
| `correlationId` | string | no |
| `timestamp` | string (date-time) | no |

### `ApiResponseLoanOfferResponse`

| Property | Type | Required |
| --- | --- | --- |
| `success` | boolean | no |
| `code` | string | no |
| `message` | string | no |
| `data` | `LoanOfferResponse` | no |
| `correlationId` | string | no |
| `timestamp` | string (date-time) | no |

### `ApiResponseLoanProductResponse`

| Property | Type | Required |
| --- | --- | --- |
| `success` | boolean | no |
| `code` | string | no |
| `message` | string | no |
| `data` | `LoanProductResponse` | no |
| `correlationId` | string | no |
| `timestamp` | string (date-time) | no |

### `ApiResponseMessageResponse`

| Property | Type | Required |
| --- | --- | --- |
| `success` | boolean | no |
| `code` | string | no |
| `message` | string | no |
| `data` | `MessageResponse` | no |
| `correlationId` | string | no |
| `timestamp` | string (date-time) | no |

### `ApiResponseMoneyReport`

| Property | Type | Required |
| --- | --- | --- |
| `success` | boolean | no |
| `code` | string | no |
| `message` | string | no |
| `data` | `MoneyReport` | no |
| `correlationId` | string | no |
| `timestamp` | string (date-time) | no |

### `ApiResponseNotificationResponse`

| Property | Type | Required |
| --- | --- | --- |
| `success` | boolean | no |
| `code` | string | no |
| `message` | string | no |
| `data` | `NotificationResponse` | no |
| `correlationId` | string | no |
| `timestamp` | string (date-time) | no |

### `ApiResponseOperationalHealth`

| Property | Type | Required |
| --- | --- | --- |
| `success` | boolean | no |
| `code` | string | no |
| `message` | string | no |
| `data` | `OperationalHealth` | no |
| `correlationId` | string | no |
| `timestamp` | string (date-time) | no |

### `ApiResponseOperationsDashboard`

| Property | Type | Required |
| --- | --- | --- |
| `success` | boolean | no |
| `code` | string | no |
| `message` | string | no |
| `data` | `OperationsDashboard` | no |
| `correlationId` | string | no |
| `timestamp` | string (date-time) | no |

### `ApiResponsePageResponseAlertResponse`

| Property | Type | Required |
| --- | --- | --- |
| `success` | boolean | no |
| `code` | string | no |
| `message` | string | no |
| `data` | `PageResponseAlertResponse` | no |
| `correlationId` | string | no |
| `timestamp` | string (date-time) | no |

### `ApiResponsePageResponseApprovalResponse`

| Property | Type | Required |
| --- | --- | --- |
| `success` | boolean | no |
| `code` | string | no |
| `message` | string | no |
| `data` | `PageResponseApprovalResponse` | no |
| `correlationId` | string | no |
| `timestamp` | string (date-time) | no |

### `ApiResponsePageResponseBranch`

| Property | Type | Required |
| --- | --- | --- |
| `success` | boolean | no |
| `code` | string | no |
| `message` | string | no |
| `data` | `PageResponseBranch` | no |
| `correlationId` | string | no |
| `timestamp` | string (date-time) | no |

### `ApiResponsePageResponseCaseResponse`

| Property | Type | Required |
| --- | --- | --- |
| `success` | boolean | no |
| `code` | string | no |
| `message` | string | no |
| `data` | `PageResponseCaseResponse` | no |
| `correlationId` | string | no |
| `timestamp` | string (date-time) | no |

### `ApiResponsePageResponseChangeRequest`

| Property | Type | Required |
| --- | --- | --- |
| `success` | boolean | no |
| `code` | string | no |
| `message` | string | no |
| `data` | `PageResponseChangeRequest` | no |
| `correlationId` | string | no |
| `timestamp` | string (date-time) | no |

### `ApiResponsePageResponseCollateralResponse`

| Property | Type | Required |
| --- | --- | --- |
| `success` | boolean | no |
| `code` | string | no |
| `message` | string | no |
| `data` | `PageResponseCollateralResponse` | no |
| `correlationId` | string | no |
| `timestamp` | string (date-time) | no |

### `ApiResponsePageResponseComplaintResponse`

| Property | Type | Required |
| --- | --- | --- |
| `success` | boolean | no |
| `code` | string | no |
| `message` | string | no |
| `data` | `PageResponseComplaintResponse` | no |
| `correlationId` | string | no |
| `timestamp` | string (date-time) | no |

### `ApiResponsePageResponseCoreTransactionResponse`

| Property | Type | Required |
| --- | --- | --- |
| `success` | boolean | no |
| `code` | string | no |
| `message` | string | no |
| `data` | `PageResponseCoreTransactionResponse` | no |
| `correlationId` | string | no |
| `timestamp` | string (date-time) | no |

### `ApiResponsePageResponseCreditQueueItemResponse`

| Property | Type | Required |
| --- | --- | --- |
| `success` | boolean | no |
| `code` | string | no |
| `message` | string | no |
| `data` | `PageResponseCreditQueueItemResponse` | no |
| `correlationId` | string | no |
| `timestamp` | string (date-time) | no |

### `ApiResponsePageResponseCustomerSummaryResponse`

| Property | Type | Required |
| --- | --- | --- |
| `success` | boolean | no |
| `code` | string | no |
| `message` | string | no |
| `data` | `PageResponseCustomerSummaryResponse` | no |
| `correlationId` | string | no |
| `timestamp` | string (date-time) | no |

### `ApiResponsePageResponseJournalResponse`

| Property | Type | Required |
| --- | --- | --- |
| `success` | boolean | no |
| `code` | string | no |
| `message` | string | no |
| `data` | `PageResponseJournalResponse` | no |
| `correlationId` | string | no |
| `timestamp` | string (date-time) | no |

### `ApiResponsePageResponseLoanAccountResponse`

| Property | Type | Required |
| --- | --- | --- |
| `success` | boolean | no |
| `code` | string | no |
| `message` | string | no |
| `data` | `PageResponseLoanAccountResponse` | no |
| `correlationId` | string | no |
| `timestamp` | string (date-time) | no |

### `ApiResponsePageResponseLoanApplicationSummaryResponse`

| Property | Type | Required |
| --- | --- | --- |
| `success` | boolean | no |
| `code` | string | no |
| `message` | string | no |
| `data` | `PageResponseLoanApplicationSummaryResponse` | no |
| `correlationId` | string | no |
| `timestamp` | string (date-time) | no |

### `ApiResponsePageResponseLoanOfferSummaryResponse`

| Property | Type | Required |
| --- | --- | --- |
| `success` | boolean | no |
| `code` | string | no |
| `message` | string | no |
| `data` | `PageResponseLoanOfferSummaryResponse` | no |
| `correlationId` | string | no |
| `timestamp` | string (date-time) | no |

### `ApiResponsePageResponseLoanProductSummaryResponse`

| Property | Type | Required |
| --- | --- | --- |
| `success` | boolean | no |
| `code` | string | no |
| `message` | string | no |
| `data` | `PageResponseLoanProductSummaryResponse` | no |
| `correlationId` | string | no |
| `timestamp` | string (date-time) | no |

### `ApiResponsePageResponseNotificationResponse`

| Property | Type | Required |
| --- | --- | --- |
| `success` | boolean | no |
| `code` | string | no |
| `message` | string | no |
| `data` | `PageResponseNotificationResponse` | no |
| `correlationId` | string | no |
| `timestamp` | string (date-time) | no |

### `ApiResponsePageResponseSystemConfiguration`

| Property | Type | Required |
| --- | --- | --- |
| `success` | boolean | no |
| `code` | string | no |
| `message` | string | no |
| `data` | `PageResponseSystemConfiguration` | no |
| `correlationId` | string | no |
| `timestamp` | string (date-time) | no |

### `ApiResponsePageResponseTemplateResponse`

| Property | Type | Required |
| --- | --- | --- |
| `success` | boolean | no |
| `code` | string | no |
| `message` | string | no |
| `data` | `PageResponseTemplateResponse` | no |
| `correlationId` | string | no |
| `timestamp` | string (date-time) | no |

### `ApiResponsePageResponseUserResponse`

| Property | Type | Required |
| --- | --- | --- |
| `success` | boolean | no |
| `code` | string | no |
| `message` | string | no |
| `data` | `PageResponseUserResponse` | no |
| `correlationId` | string | no |
| `timestamp` | string (date-time) | no |

### `ApiResponseParResponse`

| Property | Type | Required |
| --- | --- | --- |
| `success` | boolean | no |
| `code` | string | no |
| `message` | string | no |
| `data` | `ParResponse` | no |
| `correlationId` | string | no |
| `timestamp` | string (date-time) | no |

### `ApiResponseParSummary`

| Property | Type | Required |
| --- | --- | --- |
| `success` | boolean | no |
| `code` | string | no |
| `message` | string | no |
| `data` | `ParSummary` | no |
| `correlationId` | string | no |
| `timestamp` | string (date-time) | no |

### `ApiResponsePartyResponse`

| Property | Type | Required |
| --- | --- | --- |
| `success` | boolean | no |
| `code` | string | no |
| `message` | string | no |
| `data` | `PartyResponse` | no |
| `correlationId` | string | no |
| `timestamp` | string (date-time) | no |

### `ApiResponsePermissionResponse`

| Property | Type | Required |
| --- | --- | --- |
| `success` | boolean | no |
| `code` | string | no |
| `message` | string | no |
| `data` | `PermissionResponse` | no |
| `correlationId` | string | no |
| `timestamp` | string (date-time) | no |

### `ApiResponsePortfolioReport`

| Property | Type | Required |
| --- | --- | --- |
| `success` | boolean | no |
| `code` | string | no |
| `message` | string | no |
| `data` | `PortfolioReport` | no |
| `correlationId` | string | no |
| `timestamp` | string (date-time) | no |

### `ApiResponsePromiseResponse`

| Property | Type | Required |
| --- | --- | --- |
| `success` | boolean | no |
| `code` | string | no |
| `message` | string | no |
| `data` | `PromiseResponse` | no |
| `correlationId` | string | no |
| `timestamp` | string (date-time) | no |

### `ApiResponsePublicHoliday`

| Property | Type | Required |
| --- | --- | --- |
| `success` | boolean | no |
| `code` | string | no |
| `message` | string | no |
| `data` | `PublicHoliday` | no |
| `correlationId` | string | no |
| `timestamp` | string (date-time) | no |

### `ApiResponseReconciliationReport`

| Property | Type | Required |
| --- | --- | --- |
| `success` | boolean | no |
| `code` | string | no |
| `message` | string | no |
| `data` | `ReconciliationReport` | no |
| `correlationId` | string | no |
| `timestamp` | string (date-time) | no |

### `ApiResponseReconciliationResponse`

| Property | Type | Required |
| --- | --- | --- |
| `success` | boolean | no |
| `code` | string | no |
| `message` | string | no |
| `data` | `ReconciliationResponse` | no |
| `correlationId` | string | no |
| `timestamp` | string (date-time) | no |

### `ApiResponseRecoveryResponse`

| Property | Type | Required |
| --- | --- | --- |
| `success` | boolean | no |
| `code` | string | no |
| `message` | string | no |
| `data` | `RecoveryResponse` | no |
| `correlationId` | string | no |
| `timestamp` | string (date-time) | no |

### `ApiResponseRegistrationResponse`

| Property | Type | Required |
| --- | --- | --- |
| `success` | boolean | no |
| `code` | string | no |
| `message` | string | no |
| `data` | `RegistrationResponse` | no |
| `correlationId` | string | no |
| `timestamp` | string (date-time) | no |

### `ApiResponseRepaymentResponse`

| Property | Type | Required |
| --- | --- | --- |
| `success` | boolean | no |
| `code` | string | no |
| `message` | string | no |
| `data` | `RepaymentResponse` | no |
| `correlationId` | string | no |
| `timestamp` | string (date-time) | no |

### `ApiResponseRestructuringResponse`

| Property | Type | Required |
| --- | --- | --- |
| `success` | boolean | no |
| `code` | string | no |
| `message` | string | no |
| `data` | `RestructuringResponse` | no |
| `correlationId` | string | no |
| `timestamp` | string (date-time) | no |

### `ApiResponseRoleAssignmentResponse`

| Property | Type | Required |
| --- | --- | --- |
| `success` | boolean | no |
| `code` | string | no |
| `message` | string | no |
| `data` | `RoleAssignmentResponse` | no |
| `correlationId` | string | no |
| `timestamp` | string (date-time) | no |

### `ApiResponseRoleResponse`

| Property | Type | Required |
| --- | --- | --- |
| `success` | boolean | no |
| `code` | string | no |
| `message` | string | no |
| `data` | `RoleResponse` | no |
| `correlationId` | string | no |
| `timestamp` | string (date-time) | no |

### `ApiResponseScheduleResponse`

| Property | Type | Required |
| --- | --- | --- |
| `success` | boolean | no |
| `code` | string | no |
| `message` | string | no |
| `data` | `ScheduleResponse` | no |
| `correlationId` | string | no |
| `timestamp` | string (date-time) | no |

### `ApiResponseScoringModelResponse`

| Property | Type | Required |
| --- | --- | --- |
| `success` | boolean | no |
| `code` | string | no |
| `message` | string | no |
| `data` | `ScoringModelResponse` | no |
| `correlationId` | string | no |
| `timestamp` | string (date-time) | no |

### `ApiResponseScreeningResponse`

| Property | Type | Required |
| --- | --- | --- |
| `success` | boolean | no |
| `code` | string | no |
| `message` | string | no |
| `data` | `ScreeningResponse` | no |
| `correlationId` | string | no |
| `timestamp` | string (date-time) | no |

### `ApiResponseSettlementQuoteResponse`

| Property | Type | Required |
| --- | --- | --- |
| `success` | boolean | no |
| `code` | string | no |
| `message` | string | no |
| `data` | `SettlementQuoteResponse` | no |
| `correlationId` | string | no |
| `timestamp` | string (date-time) | no |

### `ApiResponseStatementResponse`

| Property | Type | Required |
| --- | --- | --- |
| `success` | boolean | no |
| `code` | string | no |
| `message` | string | no |
| `data` | `StatementResponse` | no |
| `correlationId` | string | no |
| `timestamp` | string (date-time) | no |

### `ApiResponseStoredDocumentResponse`

| Property | Type | Required |
| --- | --- | --- |
| `success` | boolean | no |
| `code` | string | no |
| `message` | string | no |
| `data` | `StoredDocumentResponse` | no |
| `correlationId` | string | no |
| `timestamp` | string (date-time) | no |

### `ApiResponseSystemConfiguration`

| Property | Type | Required |
| --- | --- | --- |
| `success` | boolean | no |
| `code` | string | no |
| `message` | string | no |
| `data` | `SystemConfiguration` | no |
| `correlationId` | string | no |
| `timestamp` | string (date-time) | no |

### `ApiResponseTemplateResponse`

| Property | Type | Required |
| --- | --- | --- |
| `success` | boolean | no |
| `code` | string | no |
| `message` | string | no |
| `data` | `TemplateResponse` | no |
| `correlationId` | string | no |
| `timestamp` | string (date-time) | no |

### `ApiResponseUUID`

| Property | Type | Required |
| --- | --- | --- |
| `success` | boolean | no |
| `code` | string | no |
| `message` | string | no |
| `data` | string (uuid) | no |
| `correlationId` | string | no |
| `timestamp` | string (date-time) | no |

### `ApiResponseUserResponse`

| Property | Type | Required |
| --- | --- | --- |
| `success` | boolean | no |
| `code` | string | no |
| `message` | string | no |
| `data` | `UserResponse` | no |
| `correlationId` | string | no |
| `timestamp` | string (date-time) | no |

### `ApiResponseValuationResponse`

| Property | Type | Required |
| --- | --- | --- |
| `success` | boolean | no |
| `code` | string | no |
| `message` | string | no |
| `data` | `ValuationResponse` | no |
| `correlationId` | string | no |
| `timestamp` | string (date-time) | no |

### `ApiResponseVersionResponse`

| Property | Type | Required |
| --- | --- | --- |
| `success` | boolean | no |
| `code` | string | no |
| `message` | string | no |
| `data` | `VersionResponse` | no |
| `correlationId` | string | no |
| `timestamp` | string (date-time) | no |

### `ApiResponseVoid`

| Property | Type | Required |
| --- | --- | --- |
| `success` | boolean | no |
| `code` | string | no |
| `message` | string | no |
| `data` | object | no |
| `correlationId` | string | no |
| `timestamp` | string (date-time) | no |

### `ApiResponseWebhookAcceptedResponse`

| Property | Type | Required |
| --- | --- | --- |
| `success` | boolean | no |
| `code` | string | no |
| `message` | string | no |
| `data` | `WebhookAcceptedResponse` | no |
| `correlationId` | string | no |
| `timestamp` | string (date-time) | no |

### `ApiResponseWriteOffReport`

| Property | Type | Required |
| --- | --- | --- |
| `success` | boolean | no |
| `code` | string | no |
| `message` | string | no |
| `data` | `WriteOffReport` | no |
| `correlationId` | string | no |
| `timestamp` | string (date-time) | no |

### `ApplicationCounts`

| Property | Type | Required |
| --- | --- | --- |
| `total` | integer (int64) | no |
| `pending` | integer (int64) | no |
| `awaitingApproval` | integer (int64) | no |
| `approved` | integer (int64) | no |
| `rejected` | integer (int64) | no |
| `disbursed` | integer (int64) | no |
| `withdrawn` | integer (int64) | no |
| `cancelled` | integer (int64) | no |
| `expired` | integer (int64) | no |

### `ApplicationDashboardResponse`

| Property | Type | Required |
| --- | --- | --- |
| `total` | integer (int64) | no |
| `drafts` | integer (int64) | no |
| `inProgress` | integer (int64) | no |
| `offers` | integer (int64) | no |
| `approved` | integer (int64) | no |
| `disbursed` | integer (int64) | no |
| `closed` | integer (int64) | no |
| `requestedAmount` | number | no |

### `ApplicationDocumentResponse`

| Property | Type | Required |
| --- | --- | --- |
| `id` | string (uuid) | no |
| `applicationId` | string (uuid) | no |
| `documentCode` | string | no |
| `documentName` | string | no |
| `fileName` | string | no |
| `contentType` | string | no |
| `sizeBytes` | integer (int64) | no |
| `checksumSha256` | string | no |
| `uploadedAt` | string (date-time) | no |

### `ApplicationHistoryResponse`

| Property | Type | Required |
| --- | --- | --- |
| `statuses` | array of `StatusChange` | no |
| `events` | array of `Event` | no |

### `ApprovalDecisionRequest`

| Property | Type | Required |
| --- | --- | --- |
| `version` | integer (int32) | yes |
| `reason` | string | yes |

### `ApprovalHistoryResponse`

| Property | Type | Required |
| --- | --- | --- |
| `id` | string (uuid) | no |
| `actorId` | string (uuid) | no |
| `role` | string | no |
| `stage` | string | no |
| `decision` | string | no |
| `amount` | number | no |
| `reason` | string | no |
| `correlationId` | string | no |
| `occurredAt` | string (date-time) | no |

### `ApprovalLevelResponse`

| Property | Type | Required |
| --- | --- | --- |
| `id` | string (uuid) | no |
| `stage` | string | no |
| `sequence` | integer (int32) | no |
| `roleCode` | string | no |
| `minAmount` | number | no |
| `maxAmount` | number | no |
| `quorum` | integer (int32) | no |
| `expiryHours` | integer (int32) | no |
| `manualRequired` | boolean | no |
| `automated` | boolean | no |
| `escalateOnExpiry` | boolean | no |

### `ApprovalMatrixResponse`

| Property | Type | Required |
| --- | --- | --- |
| `id` | string (uuid) | no |
| `code` | string | no |
| `name` | string | no |
| `description` | string | no |
| `status` | string | no |
| `createdAt` | string (date-time) | no |
| `updatedAt` | string (date-time) | no |
| `createdBy` | string | no |
| `updatedBy` | string | no |
| `versions` | array of `MatrixVersionResponse` | no |

### `ApprovalRequest`

Approval step required before a loan on this product can be booked.

| Property | Type | Required |
| --- | --- | --- |
| `stepOrder` | integer (int32) | yes |
| `roleCode` | string | yes |
| `description` | string | no |

### `ApprovalResponse`

| Property | Type | Required |
| --- | --- | --- |
| `id` | string (uuid) | no |
| `stepOrder` | integer (int32) | no |
| `roleCode` | string | no |
| `description` | string | no |

### `AssessmentFacts`

| Property | Type | Required |
| --- | --- | --- |
| `segment` | string enum | no |
| `monthlyGrossIncome` | number | no |
| `netIncome` | number | no |
| `monthlyExpenses` | number | no |
| `existingLoanObligations` | number | no |
| `proposedMonthlyInstalment` | number | no |
| `bankAccountTurnover` | number | no |
| `employmentMonths` | integer (int32) | no |
| `employmentStatus` | string enum | no |
| `repaymentHistory` | string enum | no |
| `monthlyRevenue` | number | no |
| `operatingExpenses` | number | no |
| `netOperatingCashFlow` | number | no |
| `existingLiabilities` | number | no |
| `existingDebtService` | number | no |
| `businessAgeMonths` | integer (int32) | no |
| `accountTurnover` | number | no |
| `businessSector` | string | no |
| `financialStatementsAvailable` | boolean | no |
| `financialStatementsAudited` | boolean | no |
| `eligibleCollateralValue` | number | no |
| `totalSecuredExposure` | number | no |

### `AssignRequest`

| Property | Type | Required |
| --- | --- | --- |
| `officerId` | string (uuid) | yes |
| `branchId` | string (uuid) | yes |

### `AssignRolesRequest`

| Property | Type | Required |
| --- | --- | --- |
| `roleCodes` | array of string | yes |

### `AssignmentResponse`

| Property | Type | Required |
| --- | --- | --- |
| `id` | string (uuid) | no |
| `officerId` | string (uuid) | no |
| `branchId` | string (uuid) | no |
| `assignedBy` | string (uuid) | no |
| `assignedAt` | string (date-time) | no |
| `active` | boolean | no |

### `AuditLogResponse`

| Property | Type | Required |
| --- | --- | --- |
| `id` | string (uuid) | no |
| `correlationId` | string | no |
| `actorUserId` | string (uuid) | no |
| `actor` | string | no |
| `actorRole` | string | no |
| `action` | string | no |
| `module` | string | no |
| `entityType` | string | no |
| `entityId` | string | no |
| `outcome` | string | no |
| `ipAddress` | string | no |
| `userAgent` | string | no |
| `chainSeq` | integer (int64) | no |
| `previousHash` | string | no |
| `recordHash` | string | no |
| `occurredAt` | string (date-time) | no |

### `AuditPage`

| Property | Type | Required |
| --- | --- | --- |
| `items` | array of `AuditLogResponse` | no |
| `page` | integer (int32) | no |
| `size` | integer (int32) | no |
| `total` | integer (int64) | no |
| `chainIntact` | boolean | no |

### `AuthTokenResponse`

| Property | Type | Required |
| --- | --- | --- |
| `tokenType` | string | no |
| `accessToken` | string | no |
| `refreshToken` | string | no |
| `expiresIn` | integer (int64) | no |
| `sessionId` | string (uuid) | no |
| `mfaRequired` | boolean | no |
| `mfaToken` | string | no |

### `BalanceResponse`

| Property | Type | Required |
| --- | --- | --- |
| `loanAccountId` | string (uuid) | no |
| `currencyCode` | string | no |
| `status` | string | no |
| `outstandingPrincipal` | number | no |
| `accruedInterest` | number | no |
| `outstandingInterest` | number | no |
| `outstandingFees` | number | no |
| `outstandingPenalties` | number | no |
| `overpayment` | number | no |
| `totalOutstanding` | number | no |
| `asOf` | string (date) | no |

### `BeneficialOwnerRequest`

| Property | Type | Required |
| --- | --- | --- |
| `fullName` | string | yes |
| `dateOfBirth` | string (date) | yes |
| `phoneNumber` | string | yes |
| `email` | string (email) | no |
| `ownershipPercentage` | number | yes |
| `bvn` | string | yes |
| `nin` | string | no |

### `Branch`

| Property | Type | Required |
| --- | --- | --- |
| `id` | string (uuid) | no |
| `code` | string | no |
| `name` | string | no |
| `addressLine` | string | no |
| `city` | string | no |
| `state` | string | no |
| `countryCode` | string | no |
| `phoneNumber` | string | no |
| `email` | string | no |
| `status` | string | no |

### `BranchDashboard`

| Property | Type | Required |
| --- | --- | --- |
| `generalLedger` | boolean | no |
| `branches` | array of `GroupRow` | no |

### `BranchRequest`

| Property | Type | Required |
| --- | --- | --- |
| `code` | string | yes |
| `name` | string | yes |
| `addressLine` | string | no |
| `city` | string | no |
| `state` | string | no |
| `countryCode` | string | no |
| `phoneNumber` | string | no |
| `email` | string | no |
| `status` | string | no |

### `BureauCheckResponse`

| Property | Type | Required |
| --- | --- | --- |
| `id` | string (uuid) | no |
| `providerCode` | string | no |
| `status` | string | no |
| `identificationMatched` | boolean | no |
| `bureauScore` | integer (int32) | no |
| `existingExposure` | number | no |
| `failureReason` | string | no |
| `requestedAt` | string (date-time) | no |
| `completedAt` | string (date-time) | no |

### `BusinessCalendar`

| Property | Type | Required |
| --- | --- | --- |
| `id` | string (uuid) | no |
| `code` | string | no |
| `name` | string | no |
| `timezone` | string | no |
| `weekendDays` | string | no |
| `active` | boolean | no |

### `CalculationLine`

| Property | Type | Required |
| --- | --- | --- |
| `formula` | string | no |
| `expression` | string | no |
| `inputs` | object | no |
| `output` | number | no |
| `unavailableReason` | string | no |

### `CalculationSet`

| Property | Type | Required |
| --- | --- | --- |
| `dti` | `CalculationLine` | no |
| `dscr` | `CalculationLine` | no |
| `collateralCoverage` | `CalculationLine` | no |
| `disposableIncome` | `CalculationLine` | no |

### `CalculatorConfigResponse`

Commercial inputs a calculator needs for the product version currently offered to customers.

| Property | Type | Required |
| --- | --- | --- |
| `productId` | string (uuid) | no |
| `versionId` | string (uuid) | no |
| `versionNumber` | integer (int32) | no |
| `productCode` | string | no |
| `productName` | string | no |
| `currencyCode` | string | no |
| `minimumAmount` | number | no |
| `maximumAmount` | number | no |
| `minimumTenure` | integer (int32) | no |
| `maximumTenure` | integer (int32) | no |
| `tenureUnit` | string | no |
| `interestRate` | number | no |
| `interestMethod` | string | no |
| `interestPeriod` | string | no |
| `repaymentFrequency` | string | no |
| `dayCountConvention` | string | no |
| `roundingPrecision` | integer (int32) | no |
| `roundingMethod` | string | no |
| `gracePeriodDays` | integer (int32) | no |
| `earlyRepaymentRule` | string | no |
| `earlyRepaymentFeeRate` | number | no |
| `penaltyBasis` | string | no |
| `penaltyRate` | number | no |
| `penaltyAmount` | number | no |
| `penaltyGraceDays` | integer (int32) | no |
| `fees` | array of `FeeResponse` | no |

### `CalendarDetail`

| Property | Type | Required |
| --- | --- | --- |
| `calendar` | `BusinessCalendar` | no |
| `holidays` | array of `PublicHoliday` | no |

### `CalendarRequest`

| Property | Type | Required |
| --- | --- | --- |
| `code` | string | yes |
| `name` | string | yes |
| `timezone` | string | no |
| `weekendDays` | string | no |
| `active` | boolean | no |

### `CaseDetailResponse`

| Property | Type | Required |
| --- | --- | --- |
| `collectionCase` | `CaseResponse` | no |
| `assignments` | array of `AssignmentResponse` | no |
| `activities` | array of `ActivityResponse` | no |
| `promises` | array of `PromiseResponse` | no |
| `escalations` | array of `EscalationResponse` | no |
| `recoveries` | array of `RecoveryResponse` | no |

### `CaseResponse`

| Property | Type | Required |
| --- | --- | --- |
| `id` | string (uuid) | no |
| `reference` | string | no |
| `loanAccountId` | string (uuid) | no |
| `customerId` | string (uuid) | no |
| `branchId` | string (uuid) | no |
| `status` | string | no |
| `bucket` | string | no |
| `daysPastDue` | integer (int32) | no |
| `overdueAmount` | number | no |
| `collectionExposure` | number | no |
| `missedInstalments` | integer (int32) | no |
| `openedOn` | string (date) | no |
| `closedAt` | string (date-time) | no |
| `closureOutcome` | string | no |
| `closureNote` | string | no |

### `ChangeRequest`

| Property | Type | Required |
| --- | --- | --- |
| `id` | string (uuid) | no |
| `targetType` | string | no |
| `targetKey` | string | no |
| `currentValue` | string | no |
| `proposedValue` | string | no |
| `valueType` | string | no |
| `description` | string | no |
| `sensitive` | boolean | no |
| `operation` | string | no |
| `status` | string | no |
| `requestedBy` | string (uuid) | no |
| `requestedByName` | string | no |
| `decidedBy` | string (uuid) | no |
| `decidedByName` | string | no |
| `decisionReason` | string | no |
| `requestedAt` | string (date-time) | no |
| `decidedAt` | string (date-time) | no |

### `Check`

| Property | Type | Required |
| --- | --- | --- |
| `id` | string (uuid) | no |
| `checkType` | string | no |
| `outcome` | string | no |
| `provider` | string | no |
| `reference` | string | no |
| `detail` | string | no |
| `completedAt` | string (date-time) | no |

### `CloseRequest`

| Property | Type | Required |
| --- | --- | --- |
| `outcome` | string enum | yes |
| `note` | string | yes |
| `recoveryAmount` | number | no |
| `repaymentId` | string (uuid) | no |

### `CollateralDetailResponse`

| Property | Type | Required |
| --- | --- | --- |
| `collateral` | `CollateralResponse` | no |
| `valuations` | array of `ValuationResponse` | no |
| `documents` | array of `StoredDocumentResponse` | no |
| `insurance` | `InsuranceResponse` | no |
| `encumbrances` | array of `EncumbranceResponse` | no |
| `allocations` | array of `AllocationSummaryResponse` | no |
| `releases` | array of `ReleaseResponse` | no |

### `CollateralItem`

| Property | Type | Required |
| --- | --- | --- |
| `id` | string (uuid) | no |
| `collateralType` | string | no |
| `description` | string | no |
| `estimatedValue` | number | no |
| `ownerName` | string | no |
| `location` | string | no |

### `CollateralRequest`

| Property | Type | Required |
| --- | --- | --- |
| `collateralType` | string | yes |
| `description` | string | no |
| `estimatedValue` | number | yes |
| `ownerName` | string | yes |
| `location` | string | no |

### `CollateralResponse`

| Property | Type | Required |
| --- | --- | --- |
| `id` | string (uuid) | no |
| `reference` | string | no |
| `customerId` | string (uuid) | no |
| `collateralType` | string | no |
| `description` | string | no |
| `ownerName` | string | no |
| `ownerIdentifier` | string | no |
| `identificationNumber` | string | no |
| `location` | string | no |
| `marketValue` | number | no |
| `forcedSaleValue` | number | no |
| `eligibleLendingValue` | number | no |
| `currencyCode` | string | no |
| `lendingRatio` | number | no |
| `valuationDate` | string (date) | no |
| `valuationExpiresOn` | string (date) | no |
| `valuationExpired` | boolean | no |
| `valuerName` | string | no |
| `valuerReference` | string | no |
| `verificationStatus` | string | no |
| `ownershipStatus` | string | no |
| `documentStatus` | string | no |
| `legalStatus` | string | no |
| `insuranceStatus` | string | no |
| `encumbranceStatus` | string | no |
| `allocatedAmount` | number | no |
| `availableAmount` | number | no |
| `approvedBy` | string (uuid) | no |
| `createdAt` | string (date-time) | no |
| `updatedAt` | string (date-time) | no |

### `CollectionsDashboard`

| Property | Type | Required |
| --- | --- | --- |
| `stockDate` | string (date) | no |
| `stockBasis` | string | no |
| `outstandingPrincipal` | number | no |
| `overduePrincipal` | number | no |
| `overdueCount` | integer (int64) | no |
| `par30` | number | no |
| `par60` | number | no |
| `par90` | number | no |
| `par30Principal` | number | no |
| `par60Principal` | number | no |
| `par90Principal` | number | no |
| `par30Count` | integer (int64) | no |
| `par60Count` | integer (int64) | no |
| `par90Count` | integer (int64) | no |
| `defaultRate` | number | no |
| `nonPerformingLoans` | integer (int64) | no |
| `nonPerformingPrincipal` | number | no |
| `overdueStatusCount` | integer (int64) | no |
| `postedRepaymentCash` | number | no |
| `contractualDue` | number | no |
| `collectionRate` | number | no |
| `collectionRateDefined` | boolean | no |
| `openCases` | integer (int64) | no |
| `recoveryAmount` | number | no |
| `bucketCurrent` | integer (int64) | no |
| `bucket1To30` | integer (int64) | no |
| `bucket31To60` | integer (int64) | no |
| `bucket61To90` | integer (int64) | no |
| `bucketAbove90` | integer (int64) | no |
| `parRule` | string | no |
| `bucketRule` | string | no |

### `CollectionsReportResponse`

| Property | Type | Required |
| --- | --- | --- |
| `asOfDate` | string (date) | no |
| `openCases` | integer (int64) | no |
| `closedCases` | integer (int64) | no |
| `escalatedCases` | integer (int64) | no |
| `promisesOpen` | integer (int64) | no |
| `promisesKept` | integer (int64) | no |
| `promisesBroken` | integer (int64) | no |
| `recoveredAmount` | number | no |
| `collectionExposure` | number | no |
| `collectionExposureRule` | string | no |
| `officers` | array of `OfficerPerformanceResponse` | no |

### `CommentRequest`

| Property | Type | Required |
| --- | --- | --- |
| `comment` | string | yes |

### `CommentResponse`

| Property | Type | Required |
| --- | --- | --- |
| `id` | string (uuid) | no |
| `authorId` | string (uuid) | no |
| `comment` | string | no |
| `createdAt` | string (date-time) | no |

### `CommercialTermsRequest`

Commercial terms stored on one loan product version. A later version does not change an earlier one.

| Property | Type | Required |
| --- | --- | --- |
| `productName` | string | yes |
| `description` | string | no |
| `customerSegment` | string | yes |
| `minimumAmount` | number | yes |
| `maximumAmount` | number | yes |
| `minimumTenure` | integer (int32) | yes |
| `maximumTenure` | integer (int32) | yes |
| `tenureUnit` | string | yes |
| `interestRate` | number | yes |
| `interestMethod` | string | yes |
| `interestPeriod` | string | yes |
| `repaymentFrequency` | string | yes |
| `dayCountConvention` | string | yes |
| `roundingPrecision` | integer (int32) | yes |
| `roundingMethod` | string | yes |
| `gracePeriodDays` | integer (int32) | yes |
| `earlyRepaymentRule` | string | yes |
| `earlyRepaymentFeeRate` | number | no |
| `guarantorRequired` | boolean | yes |
| `minimumGuarantors` | integer (int32) | yes |
| `guarantorNotes` | string | no |
| `creditBureauRequired` | boolean | yes |
| `minimumBureauScore` | integer (int32) | no |
| `penaltyBasis` | string | yes |
| `penaltyRate` | number | no |
| `penaltyAmount` | number | no |
| `penaltyGraceDays` | integer (int32) | yes |
| `fees` | array of `FeeRequest` | no |
| `documents` | array of `DocumentRequest` | no |
| `eligibilityRules` | array of `EligibilityRequest` | no |
| `collateralRules` | array of `CollateralRequest` | no |
| `approvalRules` | array of `ApprovalRequest` | no |

### `ComplaintDetailResponse`

| Property | Type | Required |
| --- | --- | --- |
| `complaint` | `ComplaintResponse` | no |
| `activities` | array of `ActivityResponse` | no |

### `ComplaintResponse`

| Property | Type | Required |
| --- | --- | --- |
| `id` | string (uuid) | no |
| `reference` | string | no |
| `customerId` | string (uuid) | no |
| `branchId` | string (uuid) | no |
| `category` | string | no |
| `status` | string | no |
| `subject` | string | no |
| `assigneeId` | string (uuid) | no |
| `slaDueAt` | string (date-time) | no |
| `escalatedAt` | string (date-time) | no |
| `resolvedAt` | string (date-time) | no |
| `resolution` | string | no |
| `createdAt` | string (date-time) | no |

### `ConfigRequest`

| Property | Type | Required |
| --- | --- | --- |
| `configKey` | string | yes |
| `configValue` | string | no |
| `valueType` | string | no |
| `description` | string | no |
| `sensitive` | boolean | no |

### `ConfigWrite`

| Property | Type | Required |
| --- | --- | --- |
| `pending` | boolean | no |
| `configuration` | `SystemConfiguration` | no |
| `change` | `ChangeRequest` | no |
| `featureFlag` | `FeatureFlag` | no |

### `ConsentRequest`

| Property | Type | Required |
| --- | --- | --- |
| `type` | string | yes |
| `granted` | boolean | no |
| `version` | string | yes |

### `CoreBankingHealthResponse`

| Property | Type | Required |
| --- | --- | --- |
| `provider` | string | no |
| `status` | string | no |
| `circuit` | string | no |
| `remoteCallsEnabled` | boolean | no |
| `pending` | integer (int32) | no |
| `unknown` | integer (int32) | no |
| `reconciliationRequired` | integer (int32) | no |
| `deadLetters` | integer (int32) | no |

### `CoreTransactionResponse`

| Property | Type | Required |
| --- | --- | --- |
| `id` | string (uuid) | no |
| `reference` | string | no |
| `idempotencyKey` | string | no |
| `kind` | string | no |
| `state` | string | no |
| `stage` | string | no |
| `applicationId` | string (uuid) | no |
| `customerId` | string (uuid) | no |
| `amount` | number | no |
| `appliedAmount` | number | no |
| `currencyCode` | string | no |
| `providerReference` | string | no |
| `loanAccountReference` | string | no |
| `loanAttempt` | integer (int32) | no |
| `paymentAttempt` | integer (int32) | no |
| `lastError` | string | no |
| `createdAt` | string (date-time) | no |
| `updatedAt` | string (date-time) | no |
| `resolvedAt` | string (date-time) | no |

### `CreateApprovalRequest`

| Property | Type | Required |
| --- | --- | --- |
| `applicationId` | string (uuid) | yes |
| `exceptionType` | string | no |
| `reason` | string | no |

### `CreateCollateralRequest`

| Property | Type | Required |
| --- | --- | --- |
| `customerId` | string (uuid) | yes |
| `collateralType` | string enum | yes |
| `description` | string | yes |
| `ownerName` | string | yes |
| `ownerIdentifier` | string | no |
| `identificationNumber` | string | no |
| `location` | string | no |
| `marketValue` | number | yes |
| `forcedSaleValue` | number | yes |
| `eligibleLendingValue` | number | no |
| `valuationDate` | string (date) | yes |
| `expiryDate` | string (date) | no |
| `valuerName` | string | yes |
| `valuerReference` | string | no |
| `lendingRatio` | number | no |
| `insurance` | `InsuranceRequest` | no |

### `CreateCreditAssessmentRequest`

| Property | Type | Required |
| --- | --- | --- |
| `applicationId` | string (uuid) | yes |
| `proposedMonthlyInstalment` | number | no |
| `individual` | `IndividualAssessmentRequest` | no |
| `sme` | `SmeAssessmentRequest` | no |
| `repaymentHistory` | string | no |
| `bankAccountTurnover` | number | no |
| `eligibleCollateralValue` | number | no |
| `totalSecuredExposure` | number | no |
| `businessSector` | string | no |
| `financialStatementsAvailable` | boolean | no |
| `financialStatementsAudited` | boolean | no |

### `CreateCustomerRequest`

Creates an individual or SME customer. Web and mobile clients use this same JSON body.

| Property | Type | Required |
| --- | --- | --- |
| `customerType` | string | yes |
| `category` | string | no |
| `branchId` | string (uuid) | no |
| `displayName` | string | yes |
| `email` | string (email) | yes |
| `phoneNumber` | string | yes |
| `userId` | string (uuid) | no |
| `consents` | array of `ConsentRequest` | no |

### `CreateGuarantorRequest`

| Property | Type | Required |
| --- | --- | --- |
| `fullName` | string | yes |
| `phoneNumber` | string | yes |
| `email` | string (email) | no |
| `addressLine` | string | yes |
| `bvn` | string | yes |
| `nin` | string | yes |
| `bvnVerificationReference` | string | yes |
| `ninVerificationReference` | string | yes |
| `employmentStatus` | string enum | yes |
| `employerName` | string | no |
| `jobTitle` | string | no |
| `monthlyIncome` | number | yes |
| `monthlyObligations` | number | yes |
| `consentGiven` | boolean | yes |
| `consentStatement` | string | yes |

### `CreateLoanProductRequest`

Creates a draft loan product and its first version.

| Property | Type | Required |
| --- | --- | --- |
| `productCode` | string | yes |
| `category` | string | yes |
| `terms` | `CommercialTermsRequest` | yes |

### `CreateLoanProductVersionRequest`

Opens the next draft version. The current active version stays in force for existing and new loans until this version is activated.

| Property | Type | Required |
| --- | --- | --- |
| `changeReason` | string | no |
| `terms` | `CommercialTermsRequest` | yes |

### `CreatePermissionRequest`

| Property | Type | Required |
| --- | --- | --- |
| `code` | string | yes |
| `description` | string | yes |

### `CreateRoleRequest`

| Property | Type | Required |
| --- | --- | --- |
| `code` | string | yes |
| `name` | string | yes |
| `description` | string | no |
| `requiresMfa` | boolean | no |
| `unrestrictedBranches` | boolean | no |
| `permissionCodes` | array of string | yes |

### `CreateScoringModelRequest`

| Property | Type | Required |
| --- | --- | --- |
| `code` | string | yes |
| `name` | string | yes |
| `segment` | string | yes |
| `productId` | string (uuid) | no |
| `policyVersion` | string | yes |
| `components` | array of `ScoringComponentSpec` | yes |
| `categoricalScores` | object | no |
| `riskCategories` | array of `RiskBand` | yes |
| `decisionThresholds` | `DecisionThresholds` | yes |
| `changeReason` | string | no |

### `CreateStaffRequest`

| Property | Type | Required |
| --- | --- | --- |
| `fullName` | string | yes |
| `email` | string (email) | yes |
| `phoneNumber` | string | yes |
| `password` | string | yes |
| `roleCodes` | array of string | yes |
| `branchIds` | array of string (uuid) | no |
| `primaryBranchId` | string (uuid) | no |

### `CreateUserResponse`

| Property | Type | Required |
| --- | --- | --- |
| `user` | `UserResponse` | no |
| `mfaSetup` | `MfaSetup` | no |

### `CreateValuationRequest`

| Property | Type | Required |
| --- | --- | --- |
| `kind` | string enum | no |
| `marketValue` | number | yes |
| `forcedSaleValue` | number | yes |
| `eligibleLendingValue` | number | no |
| `valuationDate` | string (date) | yes |
| `expiryDate` | string (date) | no |
| `valuerName` | string | yes |
| `valuerReference` | string | no |
| `lendingRatio` | number | no |

### `CreditAssessmentResponse`

| Property | Type | Required |
| --- | --- | --- |
| `id` | string (uuid) | no |
| `reference` | string | no |
| `applicationId` | string (uuid) | no |
| `customerId` | string (uuid) | no |
| `productId` | string (uuid) | no |
| `productVersionId` | string (uuid) | no |
| `segment` | string | no |
| `status` | string | no |
| `decision` | string | no |
| `score` | number | no |
| `riskCategory` | string | no |
| `scoringModelId` | string (uuid) | no |
| `scoringModelVersionId` | string (uuid) | no |
| `policyVersion` | string | no |
| `inputs` | `AssessmentFacts` | no |
| `calculations` | `CalculationSet` | no |
| `reasons` | array of `DecisionReason` | no |
| `analystRecommendation` | string | no |
| `analystJustification` | string | no |
| `assignedTo` | string (uuid) | no |
| `revision` | integer (int32) | no |
| `createdAt` | string (date-time) | no |
| `updatedAt` | string (date-time) | no |

### `CreditDashboard`

| Property | Type | Required |
| --- | --- | --- |
| `periodBounded` | boolean | no |
| `periodStart` | string (date) | no |
| `periodEnd` | string (date) | no |
| `applications` | `ApplicationCounts` | no |
| `approvedDecisions` | integer (int64) | no |
| `rejectedDecisions` | integer (int64) | no |
| `approvalRate` | number | no |
| `approvalRateDefined` | boolean | no |
| `rejectionRate` | number | no |
| `rejectionRateDefined` | boolean | no |
| `turnaroundDays` | number | no |
| `turnaroundSample` | integer (int64) | no |
| `averageLoanSize` | number | no |
| `riskDistribution` | array of `GroupRow` | no |
| `approvalRule` | string | no |
| `turnaroundRule` | string | no |

### `CreditQueueItemResponse`

| Property | Type | Required |
| --- | --- | --- |
| `id` | string (uuid) | no |
| `reference` | string | no |
| `applicationId` | string (uuid) | no |
| `customerId` | string (uuid) | no |
| `customerName` | string | no |
| `segment` | string | no |
| `status` | string | no |
| `decision` | string | no |
| `score` | number | no |
| `riskCategory` | string | no |
| `assignedTo` | string (uuid) | no |
| `updatedAt` | string (date-time) | no |

### `CreditReportResponse`

| Property | Type | Required |
| --- | --- | --- |
| `assessment` | `CreditAssessmentResponse` | no |
| `components` | array of `ScoreComponentResult` | no |
| `results` | array of `CreditResultSummary` | no |
| `bureauChecks` | array of `BureauCheckResponse` | no |
| `comments` | array of `CommentResponse` | no |
| `exceptions` | array of `PolicyExceptionResponse` | no |

### `CreditResultSummary`

| Property | Type | Required |
| --- | --- | --- |
| `id` | string (uuid) | no |
| `modelId` | string (uuid) | no |
| `modelVersionId` | string (uuid) | no |
| `policyVersion` | string | no |
| `score` | number | no |
| `riskCategory` | string | no |
| `decision` | string | no |
| `explanation` | string | no |
| `evaluatedAt` | string (date-time) | no |

### `CreditRuleResponse`

| Property | Type | Required |
| --- | --- | --- |
| `id` | string (uuid) | no |
| `code` | string | no |
| `name` | string | no |
| `description` | string | no |
| `segment` | string | no |
| `productId` | string (uuid) | no |
| `ruleType` | string | no |
| `mandatory` | boolean | no |
| `exceptionAllowed` | boolean | no |
| `threshold` | number | no |
| `failureOutcome` | string | no |
| `policyVersion` | string | no |
| `active` | boolean | no |

### `CustomerApplication`

| Property | Type | Required |
| --- | --- | --- |
| `reference` | string | no |
| `productCode` | string | no |
| `status` | string | no |
| `amount` | number | no |
| `currency` | string | no |
| `submittedAt` | string (date-time) | no |

### `CustomerDashboard`

| Property | Type | Required |
| --- | --- | --- |
| `currency` | string | no |
| `activeLoans` | integer (int64) | no |
| `outstandingPrincipal` | number | no |
| `outstandingInterest` | number | no |
| `outstandingFees` | number | no |
| `outstandingPenalties` | number | no |
| `nextRepaymentDate` | string (date) | no |
| `nextRepaymentAmount` | number | no |
| `statement` | `StatementSummary` | no |
| `applications` | array of `CustomerApplication` | no |
| `applicationCount` | integer (int64) | no |

### `CustomerLoan`

| Property | Type | Required |
| --- | --- | --- |
| `reference` | string | no |
| `productCode` | string | no |
| `status` | string | no |
| `outstandingPrincipal` | number | no |
| `outstandingInterest` | number | no |
| `outstandingFees` | number | no |
| `outstandingPenalties` | number | no |
| `maturityDate` | string (date) | no |
| `nextDueDate` | string (date) | no |
| `nextDueAmount` | number | no |

### `CustomerLoanPage`

| Property | Type | Required |
| --- | --- | --- |
| `loans` | array of `CustomerLoan` | no |
| `page` | integer (int32) | no |
| `size` | integer (int32) | no |
| `total` | integer (int64) | no |

### `CustomerRepayment`

| Property | Type | Required |
| --- | --- | --- |
| `reference` | string | no |
| `loanReference` | string | no |
| `valueDate` | string (date) | no |
| `amount` | number | no |
| `status` | string | no |
| `channel` | string | no |

### `CustomerRepaymentPage`

| Property | Type | Required |
| --- | --- | --- |
| `repayments` | array of `CustomerRepayment` | no |
| `page` | integer (int32) | no |
| `size` | integer (int32) | no |
| `total` | integer (int64) | no |
| `periodBounded` | boolean | no |
| `periodStart` | string (date) | no |
| `periodEnd` | string (date) | no |

### `CustomerResponse`

| Property | Type | Required |
| --- | --- | --- |
| `id` | string (uuid) | no |
| `customerReference` | string | no |
| `customerType` | string | no |
| `status` | string | no |
| `category` | string | no |
| `displayName` | string | no |
| `email` | string | no |
| `phoneNumber` | string | no |
| `userId` | string (uuid) | no |
| `branchId` | string (uuid) | no |
| `kycStatus` | string | no |
| `riskClassification` | string | no |
| `blacklisted` | boolean | no |
| `blacklistReason` | string | no |
| `complianceHold` | boolean | no |
| `complianceHoldReason` | string | no |
| `disbursementAllowed` | boolean | no |
| `disbursementBlockedReasons` | array of string | no |
| `individual` | `IndividualProfileResponse` | no |
| `sme` | `SmeProfileResponse` | no |
| `addresses` | array of `AddressResponse` | no |
| `directors` | array of `PartyResponse` | no |
| `beneficialOwners` | array of `PartyResponse` | no |
| `authorisedRepresentatives` | array of `PartyResponse` | no |
| `createdAt` | string (date-time) | no |
| `updatedAt` | string (date-time) | no |

### `CustomerSummaryResponse`

| Property | Type | Required |
| --- | --- | --- |
| `id` | string (uuid) | no |
| `customerReference` | string | no |
| `customerType` | string | no |
| `status` | string | no |
| `category` | string | no |
| `displayName` | string | no |
| `email` | string | no |
| `phoneNumber` | string | no |
| `branchId` | string (uuid) | no |
| `kycStatus` | string | no |
| `riskClassification` | string | no |
| `blacklisted` | boolean | no |
| `complianceHold` | boolean | no |

### `DecisionReason`

| Property | Type | Required |
| --- | --- | --- |
| `code` | string | no |
| `severity` | string enum | no |
| `message` | string | no |
| `blocksApproval` | boolean | no |

### `DecisionRequest`

| Property | Type | Required |
| --- | --- | --- |
| `reason` | string | no |

### `DecisionThresholds`

| Property | Type | Required |
| --- | --- | --- |
| `autoApproveMinimum` | number | no |
| `referMinimum` | number | no |

### `DefaultReport`

| Property | Type | Required |
| --- | --- | --- |
| `defaultRate` | number | no |
| `nonPerformingLoans` | integer (int64) | no |
| `nonPerformingPrincipal` | number | no |
| `denominator` | number | no |
| `rule` | string | no |
| `lines` | `DetailPage` | no |

### `DelegateApprovalRequest`

| Property | Type | Required |
| --- | --- | --- |
| `version` | integer (int32) | yes |
| `delegateUserId` | string (uuid) | yes |
| `reason` | string | yes |

### `DelegationResponse`

| Property | Type | Required |
| --- | --- | --- |
| `id` | string (uuid) | no |
| `userId` | string (uuid) | no |
| `approvalId` | string (uuid) | no |
| `roleCode` | string | no |
| `stage` | string | no |
| `branchId` | string (uuid) | no |
| `maxAmount` | number | no |
| `currencyCode` | string | no |
| `validFrom` | string (date-time) | no |
| `validUntil` | string (date-time) | no |
| `active` | boolean | no |
| `reason` | string | no |
| `grantedBy` | string (uuid) | no |
| `createdAt` | string (date-time) | no |

### `DelinquencyReport`

| Property | Type | Required |
| --- | --- | --- |
| `bucketCurrent` | integer (int64) | no |
| `bucket1To30` | integer (int64) | no |
| `bucket31To60` | integer (int64) | no |
| `bucket61To90` | integer (int64) | no |
| `bucketAbove90` | integer (int64) | no |
| `overduePrincipal` | number | no |
| `rule` | string | no |
| `lines` | `DetailPage` | no |

### `DetailPage`

| Property | Type | Required |
| --- | --- | --- |
| `lines` | array of `LoanLine` | no |
| `page` | integer (int32) | no |
| `size` | integer (int32) | no |
| `total` | integer (int64) | no |

### `DirectorRequest`

| Property | Type | Required |
| --- | --- | --- |
| `fullName` | string | yes |
| `dateOfBirth` | string (date) | yes |
| `phoneNumber` | string | yes |
| `email` | string (email) | no |
| `position` | string | no |
| `bvn` | string | yes |
| `nin` | string | no |

### `DisburseInstructionRequest`

| Property | Type | Required |
| --- | --- | --- |
| `applicationId` | string (uuid) | yes |
| `idempotencyKey` | string | yes |
| `amount` | number | no |
| `accountId` | string (uuid) | no |

### `DocumentRequest`

Document a customer must supply for this product.

| Property | Type | Required |
| --- | --- | --- |
| `documentCode` | string | yes |
| `documentName` | string | yes |
| `required` | boolean | yes |

### `DocumentRequirement`

| Property | Type | Required |
| --- | --- | --- |
| `documentCode` | string | no |
| `documentName` | string | no |
| `required` | boolean | no |
| `uploaded` | boolean | no |
| `documentId` | string (uuid) | no |

### `DocumentResponse`

| Property | Type | Required |
| --- | --- | --- |
| `id` | string (uuid) | no |
| `documentCode` | string | no |
| `documentName` | string | no |
| `required` | boolean | no |

### `EligibilityRequest`

Eligibility condition evaluated before a loan is offered.

| Property | Type | Required |
| --- | --- | --- |
| `ruleCode` | string | yes |
| `description` | string | yes |

### `EligibilityResponse`

| Property | Type | Required |
| --- | --- | --- |
| `id` | string (uuid) | no |
| `ruleCode` | string | no |
| `description` | string | no |

### `EncumbranceResponse`

| Property | Type | Required |
| --- | --- | --- |
| `id` | string (uuid) | no |
| `loanCollateralId` | string (uuid) | no |
| `source` | string | no |
| `holderName` | string | no |
| `referenceNumber` | string | no |
| `amount` | number | no |
| `status` | string | no |
| `notedAt` | string (date-time) | no |
| `releasedAt` | string (date-time) | no |

### `EscalateRequest`

| Property | Type | Required |
| --- | --- | --- |
| `reason` | string | yes |

### `EscalationResponse`

| Property | Type | Required |
| --- | --- | --- |
| `id` | string (uuid) | no |
| `reason` | string | no |
| `fromBucket` | string | no |
| `level` | integer (int32) | no |
| `status` | string | no |
| `requestedBy` | string (uuid) | no |
| `createdAt` | string (date-time) | no |

### `Event`

| Property | Type | Required |
| --- | --- | --- |
| `id` | string (uuid) | no |
| `eventType` | string | no |
| `payload` | `JsonNode` | no |
| `actorId` | string (uuid) | no |
| `occurredAt` | string (date-time) | no |

### `EventResponse`

| Property | Type | Required |
| --- | --- | --- |
| `eventType` | string | no |
| `payload` | string | no |
| `occurredAt` | string (date-time) | no |

### `ExportRequest`

| Property | Type | Required |
| --- | --- | --- |
| `report` | string enum | yes |
| `format` | string enum | yes |
| `startDate` | string (date) | no |
| `endDate` | string (date) | no |
| `branchId` | string (uuid) | no |
| `productId` | string (uuid) | no |
| `customerSegment` | string | no |
| `loanStatus` | string | no |
| `riskCategory` | string | no |
| `loanOfficerId` | string (uuid) | no |
| `security` | string | no |
| `repaymentStatus` | string | no |

### `ExportResponse`

| Property | Type | Required |
| --- | --- | --- |
| `id` | string (uuid) | no |
| `report` | string | no |
| `format` | string | no |
| `status` | string | no |
| `expiresAt` | string (date-time) | no |
| `rowCount` | integer (int32) | no |
| `fileName` | string | no |
| `downloadPath` | string | no |
| `token` | string | no |

### `FeatureFlag`

| Property | Type | Required |
| --- | --- | --- |
| `id` | string (uuid) | no |
| `key` | string | no |
| `enabled` | boolean | no |
| `description` | string | no |
| `sensitive` | boolean | no |

### `FeeRequest`

Processing or management fee. Use amount for FLAT_AMOUNT and rate for PERCENTAGE.

| Property | Type | Required |
| --- | --- | --- |
| `feeType` | string | yes |
| `name` | string | yes |
| `calculation` | string | yes |
| `amount` | number | no |
| `rate` | number | no |

### `FeeResponse`

| Property | Type | Required |
| --- | --- | --- |
| `id` | string (uuid) | no |
| `feeType` | string | no |
| `name` | string | no |
| `calculation` | string | no |
| `amount` | number | no |
| `rate` | number | no |

### `FlagRequest`

| Property | Type | Required |
| --- | --- | --- |
| `flagKey` | string | yes |
| `enabled` | boolean | no |
| `description` | string | no |
| `sensitive` | boolean | no |

### `ForgotPasswordRequest`

| Property | Type | Required |
| --- | --- | --- |
| `identifier` | string | yes |

### `GenerateOfferRequest`

Optional commercial overrides. The interest rate, method, tenure, and fees come from the approved product version.

| Property | Type | Required |
| --- | --- | --- |
| `principal` | number | no |
| `disbursementDate` | string (date) | no |
| `expiresOn` | string (date) | no |
| `issue` | boolean | no |

### `GrantDelegationRequest`

| Property | Type | Required |
| --- | --- | --- |
| `userId` | string (uuid) | yes |
| `roleCode` | string | yes |
| `stage` | string | yes |
| `branchId` | string (uuid) | no |
| `maxAmount` | number | yes |
| `validFrom` | string (date-time) | yes |
| `validUntil` | string (date-time) | no |
| `reason` | string | yes |

### `GroupReport`

| Property | Type | Required |
| --- | --- | --- |
| `rule` | string | no |
| `rows` | array of `GroupRow` | no |

### `GroupRow`

| Property | Type | Required |
| --- | --- | --- |
| `code` | string | no |
| `name` | string | no |
| `loanCount` | integer (int64) | no |
| `outstandingPrincipal` | number | no |
| `overduePrincipal` | number | no |
| `par30` | number | no |
| `par60` | number | no |
| `par90` | number | no |
| `disbursementPrincipal` | number | no |
| `collectionCash` | number | no |
| `income` | number | no |
| `applications` | integer (int64) | no |
| `pendingApplications` | integer (int64) | no |

### `GuaranteeAcceptanceRequest`

| Property | Type | Required |
| --- | --- | --- |
| `decision` | string enum | yes |
| `notes` | string | no |

### `GuarantorItem`

| Property | Type | Required |
| --- | --- | --- |
| `id` | string (uuid) | no |
| `fullName` | string | no |
| `phoneNumber` | string | no |
| `email` | string | no |
| `relationship` | string | no |
| `occupation` | string | no |
| `addressLine` | string | no |

### `GuarantorRequest`

| Property | Type | Required |
| --- | --- | --- |
| `fullName` | string | yes |
| `phoneNumber` | string | yes |
| `email` | string (email) | no |
| `relationship` | string | yes |
| `occupation` | string | no |
| `addressLine` | string | no |

### `GuarantorResponse`

| Property | Type | Required |
| --- | --- | --- |
| `id` | string (uuid) | no |
| `reference` | string | no |
| `fullName` | string | no |
| `phoneNumber` | string | no |
| `email` | string | no |
| `addressLine` | string | no |
| `bvnMasked` | string | no |
| `ninMasked` | string | no |
| `bvnVerificationReference` | string | no |
| `ninVerificationReference` | string | no |
| `employmentStatus` | string | no |
| `employerName` | string | no |
| `jobTitle` | string | no |
| `monthlyIncome` | number | no |
| `monthlyObligations` | number | no |
| `verificationStatus` | string | no |
| `acceptanceStatus` | string | no |
| `approvedSecurity` | boolean | no |
| `consentRecorded` | boolean | no |
| `rejectionReason` | string | no |
| `documents` | array of `StoredDocumentResponse` | no |
| `createdAt` | string (date-time) | no |
| `updatedAt` | string (date-time) | no |

### `GuarantorVerificationRequest`

| Property | Type | Required |
| --- | --- | --- |
| `decision` | string enum | yes |
| `notes` | string | no |

### `HolidayRequest`

| Property | Type | Required |
| --- | --- | --- |
| `holidayDate` | string (date) | no |
| `name` | string | yes |

### `IncomeReport`

| Property | Type | Required |
| --- | --- | --- |
| `accountCode` | string | no |
| `accountName` | string | no |
| `generalLedger` | number | no |
| `subledger` | number | no |
| `variance` | number | no |
| `rule` | string | no |
| `products` | array of `GroupRow` | no |

### `IndividualAssessmentRequest`

| Property | Type | Required |
| --- | --- | --- |
| `monthlyGrossIncome` | number | no |
| `netIncome` | number | no |
| `monthlyExpenses` | number | no |
| `existingLoanObligations` | number | no |
| `employmentMonths` | integer (int32) | no |
| `employmentStatus` | string | no |

### `IndividualFinancials`

| Property | Type | Required |
| --- | --- | --- |
| `monthlyIncome` | number | no |
| `otherIncome` | number | no |
| `monthlyExpenses` | number | no |
| `employmentStatus` | string | no |
| `employerName` | string | no |
| `jobTitle` | string | no |
| `employmentStartDate` | string (date) | no |

### `IndividualFinancialsRequest`

Personal income, expenses, and employment. Use this for individual customers only.

| Property | Type | Required |
| --- | --- | --- |
| `monthlyIncome` | number | no |
| `otherIncome` | number | no |
| `monthlyExpenses` | number | no |
| `employmentStatus` | string | no |
| `employerName` | string | no |
| `jobTitle` | string | no |
| `employmentStartDate` | string (date) | no |

### `IndividualProfileRequest`

| Property | Type | Required |
| --- | --- | --- |
| `fullName` | string | yes |
| `dateOfBirth` | string (date) | yes |
| `phoneNumber` | string | yes |
| `email` | string (email) | yes |
| `bvn` | string | yes |
| `nin` | string | yes |
| `residentialAddress` | `AddressRequest` | yes |
| `employmentStatus` | string | yes |
| `employerName` | string | no |
| `occupation` | string | no |
| `monthlyIncome` | number | yes |
| `monthlyExpenses` | number | yes |
| `nextOfKin` | `NextOfKinRequest` | yes |
| `consents` | array of `ConsentRequest` | no |

### `IndividualProfileResponse`

| Property | Type | Required |
| --- | --- | --- |
| `fullName` | string | no |
| `dateOfBirth` | string (date) | no |
| `phoneNumber` | string | no |
| `email` | string | no |
| `bvnMasked` | string | no |
| `ninMasked` | string | no |
| `employmentStatus` | string | no |
| `employerName` | string | no |
| `occupation` | string | no |
| `monthlyIncome` | number | no |
| `monthlyExpenses` | number | no |
| `nextOfKinName` | string | no |
| `nextOfKinRelationship` | string | no |
| `nextOfKinPhone` | string | no |
| `nextOfKinAddress` | string | no |

### `InsuranceRequest`

| Property | Type | Required |
| --- | --- | --- |
| `insurerName` | string | yes |
| `policyNumber` | string | yes |
| `coverAmount` | number | yes |
| `startDate` | string (date) | yes |
| `expiryDate` | string (date) | yes |

### `InsuranceResponse`

| Property | Type | Required |
| --- | --- | --- |
| `id` | string (uuid) | no |
| `insurerName` | string | no |
| `policyNumber` | string | no |
| `coverAmount` | number | no |
| `currencyCode` | string | no |
| `startDate` | string (date) | no |
| `expiryDate` | string (date) | no |

### `InterestAdjustmentRequest`

| Property | Type | Required |
| --- | --- | --- |
| `amount` | number | yes |
| `reason` | string | yes |

### `JournalLineResponse`

| Property | Type | Required |
| --- | --- | --- |
| `lineNumber` | integer (int32) | no |
| `accountCode` | string | no |
| `direction` | string | no |
| `amount` | number | no |
| `currencyCode` | string | no |
| `narration` | string | no |

### `JournalResponse`

| Property | Type | Required |
| --- | --- | --- |
| `id` | string (uuid) | no |
| `reference` | string | no |
| `loanAccountId` | string (uuid) | no |
| `eventCode` | string | no |
| `currencyCode` | string | no |
| `status` | string | no |
| `reversalOf` | string (uuid) | no |
| `requiresApproval` | boolean | no |
| `requestedBy` | string (uuid) | no |
| `approvedBy` | string (uuid) | no |
| `postedAt` | string (date-time) | no |
| `lines` | array of `JournalLineResponse` | no |

### `KycResponse`

| Property | Type | Required |
| --- | --- | --- |
| `customerId` | string (uuid) | no |
| `kycStatus` | string | no |
| `complianceHold` | boolean | no |
| `complianceHoldReason` | string | no |
| `disbursementAllowed` | boolean | no |
| `disbursementBlockedReasons` | array of string | no |
| `checks` | array of `Check` | no |
| `reviewCases` | array of `ReviewCase` | no |

### `LevelRequest`

| Property | Type | Required |
| --- | --- | --- |
| `stage` | string | yes |
| `sequence` | integer (int32) | yes |
| `roleCode` | string | yes |
| `minAmount` | number | no |
| `maxAmount` | number | no |
| `quorum` | integer (int32) | yes |
| `expiryHours` | integer (int32) | yes |
| `manualRequired` | boolean | yes |
| `automated` | boolean | yes |
| `escalateOnExpiry` | boolean | yes |

### `LinkGuarantorRequest`

| Property | Type | Required |
| --- | --- | --- |
| `guarantorId` | string (uuid) | yes |
| `relationship` | string | yes |

### `LoanAccountResponse`

| Property | Type | Required |
| --- | --- | --- |
| `id` | string (uuid) | no |
| `reference` | string | no |
| `applicationId` | string (uuid) | no |
| `customerId` | string (uuid) | no |
| `branchId` | string (uuid) | no |
| `currencyCode` | string | no |
| `status` | string | no |
| `originalPrincipal` | number | no |
| `outstandingPrincipal` | number | no |
| `accruedInterest` | number | no |
| `outstandingInterest` | number | no |
| `outstandingFees` | number | no |
| `outstandingPenalties` | number | no |
| `overpayment` | number | no |
| `totalOutstanding` | number | no |
| `disbursementDate` | string (date) | no |
| `maturityDate` | string (date) | no |
| `accrualStart` | string (date) | no |
| `lastAccruedThrough` | string (date) | no |
| `interestMethod` | string | no |
| `interestRate` | number | no |
| `repaymentFrequency` | string | no |
| `dayCountConvention` | string | no |
| `scheduleVersion` | integer (int32) | no |
| `coreDisbursementReference` | string | no |
| `activatedAt` | string (date-time) | no |
| `closedAt` | string (date-time) | no |

### `LoanApplicationResponse`

| Property | Type | Required |
| --- | --- | --- |
| `id` | string (uuid) | no |
| `reference` | string | no |
| `customerId` | string (uuid) | no |
| `customerSegment` | string | no |
| `productId` | string (uuid) | no |
| `productCode` | string | no |
| `productName` | string | no |
| `productVersionId` | string (uuid) | no |
| `productVersionNumber` | integer (int32) | no |
| `status` | string | no |
| `revisionNumber` | integer (int32) | no |
| `correctionOpen` | boolean | no |
| `amount` | number | no |
| `currencyCode` | string | no |
| `purpose` | string | no |
| `tenure` | integer (int32) | no |
| `tenureUnit` | string | no |
| `repaymentFrequency` | string | no |
| `disbursementAccountId` | string (uuid) | no |
| `disbursementAccountMasked` | string | no |
| `individualFinancials` | `IndividualFinancials` | no |
| `smeFinancials` | `SmeFinancials` | no |
| `obligations` | array of `ObligationItem` | no |
| `guarantors` | array of `GuarantorItem` | no |
| `collaterals` | array of `CollateralItem` | no |
| `documents` | array of `DocumentRequirement` | no |
| `channel` | string | no |
| `submittedAt` | string (date-time) | no |
| `withdrawnAt` | string (date-time) | no |
| `decisionReason` | string | no |
| `createdAt` | string (date-time) | no |
| `updatedAt` | string (date-time) | no |

### `LoanApplicationSummaryResponse`

| Property | Type | Required |
| --- | --- | --- |
| `id` | string (uuid) | no |
| `reference` | string | no |
| `customerId` | string (uuid) | no |
| `customerName` | string | no |
| `customerSegment` | string | no |
| `productId` | string (uuid) | no |
| `productCode` | string | no |
| `productName` | string | no |
| `status` | string | no |
| `amount` | number | no |
| `currencyCode` | string | no |
| `tenure` | integer (int32) | no |
| `tenureUnit` | string | no |
| `repaymentFrequency` | string | no |
| `revisionNumber` | integer (int32) | no |
| `correctionOpen` | boolean | no |
| `channel` | string | no |
| `submittedAt` | string (date-time) | no |
| `updatedAt` | string (date-time) | no |

### `LoanCollateralListResponse`

| Property | Type | Required |
| --- | --- | --- |
| `facilityAmount` | number | no |
| `totalAllocated` | number | no |
| `coverageRatio` | number | no |
| `items` | array of `LoanCollateralResponse` | no |

### `LoanCollateralResponse`

| Property | Type | Required |
| --- | --- | --- |
| `id` | string (uuid) | no |
| `applicationId` | string (uuid) | no |
| `collateralId` | string (uuid) | no |
| `collateralReference` | string | no |
| `collateralType` | string | no |
| `allocatedAmount` | number | no |
| `sharedSecurity` | boolean | no |
| `status` | string | no |
| `eligibleLendingValue` | number | no |
| `availableAmount` | number | no |
| `coverageRatio` | number | no |
| `collateralStatus` | string | no |

### `LoanGuarantorResponse`

| Property | Type | Required |
| --- | --- | --- |
| `id` | string (uuid) | no |
| `applicationId` | string (uuid) | no |
| `guarantorId` | string (uuid) | no |
| `guarantorReference` | string | no |
| `fullName` | string | no |
| `relationship` | string | no |
| `approvedSecurity` | boolean | no |
| `verificationStatus` | string | no |
| `acceptanceStatus` | string | no |
| `consentRecorded` | boolean | no |
| `linkedAt` | string (date-time) | no |

### `LoanLine`

| Property | Type | Required |
| --- | --- | --- |
| `journalReference` | string | no |
| `loanReference` | string | no |
| `eventDate` | string (date) | no |
| `amount` | number | no |
| `principal` | number | no |
| `interest` | number | no |
| `fees` | number | no |
| `penalties` | number | no |
| `status` | string | no |
| `event` | string | no |
| `branch` | string | no |
| `product` | string | no |
| `segment` | string | no |
| `security` | string | no |
| `risk` | string | no |
| `customerName` | string | no |
| `daysPastDue` | integer (int32) | no |
| `bucket` | string | no |

### `LoanOfferResponse`

| Property | Type | Required |
| --- | --- | --- |
| `id` | string (uuid) | no |
| `reference` | string | no |
| `applicationId` | string (uuid) | no |
| `applicationReference` | string | no |
| `customerId` | string (uuid) | no |
| `customerName` | string | no |
| `customerReference` | string | no |
| `status` | string | no |
| `expiresAt` | string (date-time) | no |
| `acceptedAt` | string (date-time) | no |
| `version` | integer (int32) | no |
| `offerVersion` | integer (int32) | no |
| `engineVersion` | string | no |
| `calculationFingerprint` | string | no |
| `terms` | `TermsResponse` | no |
| `agreementId` | string (uuid) | no |
| `agreementChecksum` | string | no |
| `signatureDisclaimer` | string | no |

### `LoanOfferSummaryResponse`

| Property | Type | Required |
| --- | --- | --- |
| `id` | string (uuid) | no |
| `reference` | string | no |
| `applicationId` | string (uuid) | no |
| `applicationReference` | string | no |
| `productName` | string | no |
| `status` | string | no |
| `principal` | number | no |
| `currencyCode` | string | no |
| `instalmentAmount` | number | no |
| `expiresAt` | string (date-time) | no |
| `version` | integer (int32) | no |

### `LoanProductResponse`

Loan product and the version currently in force, plus any unpublished version.

| Property | Type | Required |
| --- | --- | --- |
| `id` | string (uuid) | no |
| `productCode` | string | no |
| `category` | string | no |
| `status` | string | no |
| `currentVersion` | `LoanProductVersionResponse` | no |
| `pendingVersionId` | string (uuid) | no |
| `pendingVersionStatus` | string | no |
| `createdAt` | string (date-time) | no |
| `updatedAt` | string (date-time) | no |

### `LoanProductSummaryResponse`

Catalogue row for a loan product.

| Property | Type | Required |
| --- | --- | --- |
| `id` | string (uuid) | no |
| `productCode` | string | no |
| `productName` | string | no |
| `category` | string | no |
| `status` | string | no |
| `customerSegment` | string | no |
| `currencyCode` | string | no |
| `minimumAmount` | number | no |
| `maximumAmount` | number | no |
| `minimumTenure` | integer (int32) | no |
| `maximumTenure` | integer (int32) | no |
| `tenureUnit` | string | no |
| `interestRate` | number | no |
| `interestMethod` | string | no |
| `repaymentFrequency` | string | no |
| `versionNumber` | integer (int32) | no |

### `LoanProductVersionResponse`

One immutable product version once it leaves draft, including fees, documents, and rules.

| Property | Type | Required |
| --- | --- | --- |
| `id` | string (uuid) | no |
| `versionNumber` | integer (int32) | no |
| `status` | string | no |
| `productName` | string | no |
| `description` | string | no |
| `customerSegment` | string | no |
| `currencyCode` | string | no |
| `minimumAmount` | number | no |
| `maximumAmount` | number | no |
| `minimumTenure` | integer (int32) | no |
| `maximumTenure` | integer (int32) | no |
| `tenureUnit` | string | no |
| `interestRate` | number | no |
| `interestMethod` | string | no |
| `interestPeriod` | string | no |
| `repaymentFrequency` | string | no |
| `dayCountConvention` | string | no |
| `roundingPrecision` | integer (int32) | no |
| `roundingMethod` | string | no |
| `gracePeriodDays` | integer (int32) | no |
| `earlyRepaymentRule` | string | no |
| `earlyRepaymentFeeRate` | number | no |
| `guarantorRequired` | boolean | no |
| `minimumGuarantors` | integer (int32) | no |
| `guarantorNotes` | string | no |
| `creditBureauRequired` | boolean | no |
| `minimumBureauScore` | integer (int32) | no |
| `penaltyBasis` | string | no |
| `penaltyRate` | number | no |
| `penaltyAmount` | number | no |
| `penaltyGraceDays` | integer (int32) | no |
| `changeReason` | string | no |
| `effectiveFrom` | string (date-time) | no |
| `effectiveTo` | string (date-time) | no |
| `submittedBy` | string | no |
| `submittedAt` | string (date-time) | no |
| `approvedBy` | string | no |
| `approvedAt` | string (date-time) | no |
| `activatedBy` | string | no |
| `activatedAt` | string (date-time) | no |
| `fees` | array of `FeeResponse` | no |
| `documents` | array of `DocumentResponse` | no |
| `eligibilityRules` | array of `EligibilityResponse` | no |
| `collateralRules` | array of `CollateralResponse` | no |
| `approvalRules` | array of `ApprovalResponse` | no |

### `LoginRequest`

| Property | Type | Required |
| --- | --- | --- |
| `identifier` | string | yes |
| `password` | string | yes |

### `MatrixVersionResponse`

| Property | Type | Required |
| --- | --- | --- |
| `id` | string (uuid) | no |
| `versionNumber` | integer (int32) | no |
| `status` | string | no |
| `productId` | string (uuid) | no |
| `customerSegment` | string | no |
| `branchId` | string (uuid) | no |
| `riskRating` | string | no |
| `collateralStatus` | string | no |
| `securityClassification` | string | no |
| `exceptionType` | string | no |
| `automatedAllowed` | boolean | no |
| `amountMin` | number | no |
| `amountMax` | number | no |
| `activatedBy` | string | no |
| `activatedAt` | string (date-time) | no |
| `createdBy` | string | no |
| `levels` | array of `ApprovalLevelResponse` | no |

### `MeasureRow`

| Property | Type | Required |
| --- | --- | --- |
| `measure` | string | no |
| `subledger` | number | no |
| `generalLedger` | number | no |
| `variance` | number | no |
| `note` | string | no |

### `MessageResponse`

| Property | Type | Required |
| --- | --- | --- |
| `message` | string | no |

### `MfaSetup`

| Property | Type | Required |
| --- | --- | --- |
| `secret` | string | no |
| `otpauthUri` | string | no |

### `MfaVerifyRequest`

| Property | Type | Required |
| --- | --- | --- |
| `mfaToken` | string | yes |
| `code` | string | yes |

### `MoneyReport`

| Property | Type | Required |
| --- | --- | --- |
| `generalLedger` | number | no |
| `subledger` | number | no |
| `variance` | number | no |
| `count` | integer (int64) | no |
| `rule` | string | no |
| `lines` | `DetailPage` | no |

### `NextOfKinRequest`

| Property | Type | Required |
| --- | --- | --- |
| `fullName` | string | yes |
| `relationship` | string | yes |
| `phoneNumber` | string | yes |
| `address` | string | yes |

### `NoteRequest`

| Property | Type | Required |
| --- | --- | --- |
| `note` | string | yes |

### `NotificationResponse`

| Property | Type | Required |
| --- | --- | --- |
| `id` | string (uuid) | no |
| `customerId` | string (uuid) | no |
| `eventType` | string | no |
| `channel` | string | no |
| `subject` | string | no |
| `body` | string | no |
| `status` | string | no |
| `attemptCount` | integer (int32) | no |
| `lastError` | string | no |
| `createdAt` | string (date-time) | no |
| `sentAt` | string (date-time) | no |
| `readAt` | string (date-time) | no |

### `ObligationItem`

| Property | Type | Required |
| --- | --- | --- |
| `lender` | string | no |
| `outstandingBalance` | number | no |
| `monthlyRepayment` | number | no |

### `ObligationRequest`

An existing loan or credit obligation.

| Property | Type | Required |
| --- | --- | --- |
| `lender` | string | yes |
| `outstandingBalance` | number | yes |
| `monthlyRepayment` | number | yes |

### `OfferReasonRequest`

| Property | Type | Required |
| --- | --- | --- |
| `version` | integer (int32) | yes |
| `reason` | string | yes |

### `OfficerPerformanceResponse`

| Property | Type | Required |
| --- | --- | --- |
| `officerId` | string (uuid) | no |
| `activeCases` | integer (int64) | no |
| `recoveredAmount` | number | no |

### `OperationalHealth`

| Property | Type | Required |
| --- | --- | --- |
| `database` | boolean | no |
| `pendingNotifications` | integer (int64) | no |
| `deadNotifications` | integer (int64) | no |
| `openComplianceCases` | integer (int64) | no |
| `breachedComplaints` | integer (int64) | no |
| `pendingApprovals` | integer (int64) | no |

### `OperationsDashboard`

| Property | Type | Required |
| --- | --- | --- |
| `currency` | string | no |
| `stockDate` | string (date) | no |
| `stockBasis` | string | no |
| `periodBounded` | boolean | no |
| `periodStart` | string (date) | no |
| `periodEnd` | string (date) | no |
| `applications` | `ApplicationCounts` | no |
| `disbursedLoans` | integer (int64) | no |
| `disbursementPrincipal` | number | no |
| `outstandingPrincipal` | number | no |
| `outstandingInterest` | number | no |
| `overduePrincipal` | number | no |
| `postedRepaymentCash` | number | no |
| `contractualDue` | number | no |
| `collectionRate` | number | no |
| `collectionRateDefined` | boolean | no |

### `PageResponseAlertResponse`

| Property | Type | Required |
| --- | --- | --- |
| `items` | array of `AlertResponse` | no |
| `page` | integer (int32) | no |
| `size` | integer (int32) | no |
| `total` | integer (int64) | no |

### `PageResponseApprovalResponse`

| Property | Type | Required |
| --- | --- | --- |
| `items` | array of `ApprovalResponse` | no |
| `page` | integer (int32) | no |
| `size` | integer (int32) | no |
| `total` | integer (int64) | no |

### `PageResponseBranch`

| Property | Type | Required |
| --- | --- | --- |
| `items` | array of `Branch` | no |
| `page` | integer (int32) | no |
| `size` | integer (int32) | no |
| `total` | integer (int64) | no |

### `PageResponseCaseResponse`

| Property | Type | Required |
| --- | --- | --- |
| `items` | array of `CaseResponse` | no |
| `page` | integer (int32) | no |
| `size` | integer (int32) | no |
| `total` | integer (int64) | no |

### `PageResponseChangeRequest`

| Property | Type | Required |
| --- | --- | --- |
| `items` | array of `ChangeRequest` | no |
| `page` | integer (int32) | no |
| `size` | integer (int32) | no |
| `total` | integer (int64) | no |

### `PageResponseCollateralResponse`

| Property | Type | Required |
| --- | --- | --- |
| `items` | array of `CollateralResponse` | no |
| `page` | integer (int32) | no |
| `size` | integer (int32) | no |
| `total` | integer (int64) | no |

### `PageResponseComplaintResponse`

| Property | Type | Required |
| --- | --- | --- |
| `items` | array of `ComplaintResponse` | no |
| `page` | integer (int32) | no |
| `size` | integer (int32) | no |
| `total` | integer (int64) | no |

### `PageResponseCoreTransactionResponse`

| Property | Type | Required |
| --- | --- | --- |
| `items` | array of `CoreTransactionResponse` | no |
| `page` | integer (int32) | no |
| `size` | integer (int32) | no |
| `total` | integer (int64) | no |

### `PageResponseCreditQueueItemResponse`

| Property | Type | Required |
| --- | --- | --- |
| `items` | array of `CreditQueueItemResponse` | no |
| `page` | integer (int32) | no |
| `size` | integer (int32) | no |
| `total` | integer (int64) | no |

### `PageResponseCustomerSummaryResponse`

| Property | Type | Required |
| --- | --- | --- |
| `items` | array of `CustomerSummaryResponse` | no |
| `page` | integer (int32) | no |
| `size` | integer (int32) | no |
| `total` | integer (int64) | no |

### `PageResponseJournalResponse`

| Property | Type | Required |
| --- | --- | --- |
| `items` | array of `JournalResponse` | no |
| `page` | integer (int32) | no |
| `size` | integer (int32) | no |
| `total` | integer (int64) | no |

### `PageResponseLoanAccountResponse`

| Property | Type | Required |
| --- | --- | --- |
| `items` | array of `LoanAccountResponse` | no |
| `page` | integer (int32) | no |
| `size` | integer (int32) | no |
| `total` | integer (int64) | no |

### `PageResponseLoanApplicationSummaryResponse`

| Property | Type | Required |
| --- | --- | --- |
| `items` | array of `LoanApplicationSummaryResponse` | no |
| `page` | integer (int32) | no |
| `size` | integer (int32) | no |
| `total` | integer (int64) | no |

### `PageResponseLoanOfferSummaryResponse`

| Property | Type | Required |
| --- | --- | --- |
| `items` | array of `LoanOfferSummaryResponse` | no |
| `page` | integer (int32) | no |
| `size` | integer (int32) | no |
| `total` | integer (int64) | no |

### `PageResponseLoanProductSummaryResponse`

| Property | Type | Required |
| --- | --- | --- |
| `items` | array of `LoanProductSummaryResponse` | no |
| `page` | integer (int32) | no |
| `size` | integer (int32) | no |
| `total` | integer (int64) | no |

### `PageResponseNotificationResponse`

| Property | Type | Required |
| --- | --- | --- |
| `items` | array of `NotificationResponse` | no |
| `page` | integer (int32) | no |
| `size` | integer (int32) | no |
| `total` | integer (int64) | no |

### `PageResponseSystemConfiguration`

| Property | Type | Required |
| --- | --- | --- |
| `items` | array of `SystemConfiguration` | no |
| `page` | integer (int32) | no |
| `size` | integer (int32) | no |
| `total` | integer (int64) | no |

### `PageResponseTemplateResponse`

| Property | Type | Required |
| --- | --- | --- |
| `items` | array of `TemplateResponse` | no |
| `page` | integer (int32) | no |
| `size` | integer (int32) | no |
| `total` | integer (int64) | no |

### `PageResponseUserResponse`

| Property | Type | Required |
| --- | --- | --- |
| `items` | array of `UserResponse` | no |
| `page` | integer (int32) | no |
| `size` | integer (int32) | no |
| `total` | integer (int64) | no |

### `ParBandResponse`

| Property | Type | Required |
| --- | --- | --- |
| `thresholdDays` | integer (int32) | no |
| `accounts` | integer (int32) | no |
| `outstandingPrincipal` | number | no |
| `ratio` | number | no |

### `ParResponse`

| Property | Type | Required |
| --- | --- | --- |
| `asOfDate` | string (date) | no |
| `denominatorRule` | string | no |
| `numeratorRule` | string | no |
| `daysPastDueRule` | string | no |
| `confirmedAllocationRule` | string | no |
| `collectionExposureRule` | string | no |
| `denominatorOutstandingPrincipal` | number | no |
| `accountsInDenominator` | integer (int32) | no |
| `par30` | `ParBandResponse` | no |
| `par60` | `ParBandResponse` | no |
| `par90` | `ParBandResponse` | no |

### `ParSummary`

| Property | Type | Required |
| --- | --- | --- |
| `stockDate` | string (date) | no |
| `stockBasis` | string | no |
| `denominator` | number | no |
| `loanCount` | integer (int64) | no |
| `par30` | number | no |
| `par60` | number | no |
| `par90` | number | no |
| `par30Principal` | number | no |
| `par60Principal` | number | no |
| `par90Principal` | number | no |
| `rule` | string | no |

### `PartyResponse`

| Property | Type | Required |
| --- | --- | --- |
| `id` | string (uuid) | no |
| `fullName` | string | no |
| `dateOfBirth` | string (date) | no |
| `phoneNumber` | string | no |
| `email` | string | no |
| `position` | string | no |
| `ownershipPercentage` | number | no |
| `bvnMasked` | string | no |
| `ninMasked` | string | no |

### `PermissionResponse`

| Property | Type | Required |
| --- | --- | --- |
| `id` | string (uuid) | no |
| `code` | string | no |
| `description` | string | no |
| `systemPermission` | boolean | no |

### `PolicyExceptionResponse`

| Property | Type | Required |
| --- | --- | --- |
| `id` | string (uuid) | no |
| `ruleCode` | string | no |
| `status` | string | no |
| `justification` | string | no |
| `requestedBy` | string (uuid) | no |
| `requestedAt` | string (date-time) | no |
| `decidedBy` | string (uuid) | no |
| `decidedAt` | string (date-time) | no |
| `decisionNote` | string | no |

### `PortfolioReport`

| Property | Type | Required |
| --- | --- | --- |
| `currency` | string | no |
| `stockDate` | string (date) | no |
| `stockBasis` | string | no |
| `periodBounded` | boolean | no |
| `periodStart` | string (date) | no |
| `periodEnd` | string (date) | no |
| `latestSnapshot` | string (date) | no |
| `totalDisbursements` | number | no |
| `disbursedLoans` | integer (int64) | no |
| `averageLoanSize` | number | no |
| `outstandingPrincipal` | number | no |
| `outstandingInterest` | number | no |
| `totalRepaymentCollections` | number | no |
| `collectionRate` | number | no |
| `collectionRateDefined` | boolean | no |
| `par30` | number | no |
| `par60` | number | no |
| `par90` | number | no |
| `par30Principal` | number | no |
| `par60Principal` | number | no |
| `par90Principal` | number | no |
| `defaultRate` | number | no |
| `nonPerformingLoans` | integer (int64) | no |
| `nonPerformingPrincipal` | number | no |
| `approvalRate` | number | no |
| `approvalRateDefined` | boolean | no |
| `rejectionRate` | number | no |
| `rejectionRateDefined` | boolean | no |
| `turnaroundDays` | number | no |
| `turnaroundSample` | integer (int64) | no |
| `individualAndSme` | array of `GroupRow` | no |
| `securedAndUnsecured` | array of `GroupRow` | no |
| `creditRisk` | array of `GroupRow` | no |
| `products` | array of `GroupRow` | no |
| `branches` | array of `GroupRow` | no |
| `rules` | `Rules` | no |

### `PreferenceItem`

| Property | Type | Required |
| --- | --- | --- |
| `channel` | string | yes |
| `eventType` | string | yes |
| `enabled` | boolean | no |

### `PreferenceResponse`

| Property | Type | Required |
| --- | --- | --- |
| `id` | string (uuid) | no |
| `channel` | string | no |
| `eventType` | string | no |
| `enabled` | boolean | no |

### `PreferenceUpdateRequest`

| Property | Type | Required |
| --- | --- | --- |
| `preferences` | array of `PreferenceItem` | yes |

### `ProductActionRequest`

Optional note and effective date for a product lifecycle action.

| Property | Type | Required |
| --- | --- | --- |
| `reason` | string | no |
| `effectiveFrom` | string (date-time) | no |

### `PromiseRequest`

| Property | Type | Required |
| --- | --- | --- |
| `amount` | number | yes |
| `promiseDate` | string (date) | yes |
| `note` | string | no |

### `PromiseResponse`

| Property | Type | Required |
| --- | --- | --- |
| `id` | string (uuid) | no |
| `amount` | number | no |
| `promiseDate` | string (date) | no |
| `status` | string | no |
| `note` | string | no |
| `createdAt` | string (date-time) | no |
| `resolvedAt` | string (date-time) | no |

### `PublicHoliday`

| Property | Type | Required |
| --- | --- | --- |
| `id` | string (uuid) | no |
| `calendarId` | string (uuid) | no |
| `holidayDate` | string (date) | no |
| `name` | string | no |

### `ReasonRequest`

| Property | Type | Required |
| --- | --- | --- |
| `reason` | string | yes |

### `RecommendRequest`

| Property | Type | Required |
| --- | --- | --- |
| `recommendation` | string | yes |
| `justification` | string | yes |
| `exceptionId` | string (uuid) | no |

### `ReconciliationReport`

| Property | Type | Required |
| --- | --- | --- |
| `balancesAreCurrent` | boolean | no |
| `rule` | string | no |
| `measures` | array of `MeasureRow` | no |

### `ReconciliationResponse`

| Property | Type | Required |
| --- | --- | --- |
| `batchId` | string (uuid) | no |
| `transactionId` | string (uuid) | no |
| `transactionState` | string | no |
| `itemStatus` | string | no |
| `expectedAmount` | number | no |
| `actualAmount` | number | no |

### `RecoveryRequest`

| Property | Type | Required |
| --- | --- | --- |
| `amount` | number | yes |
| `repaymentId` | string (uuid) | yes |
| `note` | string | no |

### `RecoveryResponse`

| Property | Type | Required |
| --- | --- | --- |
| `id` | string (uuid) | no |
| `repaymentId` | string (uuid) | no |
| `amount` | number | no |
| `outcome` | string | no |
| `status` | string | no |
| `recordedAt` | string (date-time) | no |

### `RefreshTokenRequest`

| Property | Type | Required |
| --- | --- | --- |
| `refreshToken` | string | yes |

### `RegisterCustomerRequest`

| Property | Type | Required |
| --- | --- | --- |
| `customerType` | string | yes |
| `fullName` | string | yes |
| `email` | string (email) | yes |
| `phoneNumber` | string | yes |
| `password` | string | yes |
| `businessName` | string | no |

### `RegistrationResponse`

| Property | Type | Required |
| --- | --- | --- |
| `userId` | string (uuid) | no |
| `status` | string | no |
| `verificationRequired` | array of string | no |

### `RejectRequest`

| Property | Type | Required |
| --- | --- | --- |
| `reason` | string | yes |

### `RelatedAgreement`

| Property | Type | Required |
| --- | --- | --- |
| `id` | string (uuid) | no |
| `kind` | string | no |
| `checksumSha256` | string | no |
| `superseded` | boolean | no |

### `ReleaseCollateralRequest`

| Property | Type | Required |
| --- | --- | --- |
| `settlementReference` | string | yes |
| `dischargeReference` | string | yes |
| `reason` | string | yes |

### `ReleaseResponse`

| Property | Type | Required |
| --- | --- | --- |
| `id` | string (uuid) | no |
| `settlementReference` | string | no |
| `dischargeReference` | string | no |
| `reason` | string | no |
| `releasedBy` | string (uuid) | no |
| `releasedAt` | string (date-time) | no |

### `RepaymentInstructionRequest`

| Property | Type | Required |
| --- | --- | --- |
| `applicationId` | string (uuid) | yes |
| `idempotencyKey` | string | yes |
| `amount` | number | yes |

### `RepaymentRequest`

| Property | Type | Required |
| --- | --- | --- |
| `loanAccountId` | string (uuid) | yes |
| `amount` | number | yes |
| `channel` | string enum | yes |
| `idempotencyKey` | string | yes |
| `valueDate` | string (date) | no |
| `externalReference` | string | no |

### `RepaymentResponse`

| Property | Type | Required |
| --- | --- | --- |
| `id` | string (uuid) | no |
| `reference` | string | no |
| `loanAccountId` | string (uuid) | no |
| `channel` | string | no |
| `status` | string | no |
| `amount` | number | no |
| `valueDate` | string (date) | no |
| `settlement` | boolean | no |
| `feeCharged` | number | no |
| `coreReference` | string | no |
| `externalReference` | string | no |
| `allocationPolicyVersion` | integer (int32) | no |
| `failureReason` | string | no |
| `allocations` | array of `AllocationResponse` | no |

### `ReportQuery`

| Property | Type | Required |
| --- | --- | --- |
| `startDate` | string (date) | no |
| `endDate` | string (date) | no |
| `branchId` | string (uuid) | no |
| `productId` | string (uuid) | no |
| `customerSegment` | string | no |
| `loanStatus` | string | no |
| `riskCategory` | string | no |
| `loanOfficerId` | string (uuid) | no |
| `security` | string | no |
| `repaymentStatus` | string | no |
| `page` | integer (int32) | no |
| `size` | integer (int32) | no |

### `RepresentativeRequest`

| Property | Type | Required |
| --- | --- | --- |
| `fullName` | string | yes |
| `phoneNumber` | string | yes |
| `email` | string (email) | no |
| `position` | string | yes |

### `ResetPasswordRequest`

| Property | Type | Required |
| --- | --- | --- |
| `identifier` | string | yes |
| `code` | string | yes |
| `newPassword` | string | yes |

### `ResolveRequest`

| Property | Type | Required |
| --- | --- | --- |
| `decision` | string | yes |
| `reason` | string | yes |
| `riskClassification` | string | no |

### `RestructureRequest`

| Property | Type | Required |
| --- | --- | --- |
| `tenure` | integer (int32) | no |
| `interestRate` | number | yes |
| `capitalizeInterest` | boolean | no |
| `repaymentDayOfMonth` | integer (int32) | no |
| `holidayRule` | string enum | no |
| `reason` | string | yes |
| `valueDate` | string (date) | no |

### `RestructuringApprovalResponse`

| Property | Type | Required |
| --- | --- | --- |
| `decision` | string | no |
| `approverId` | string (uuid) | no |
| `reason` | string | no |
| `decidedAt` | string (date-time) | no |

### `RestructuringRequest`

| Property | Type | Required |
| --- | --- | --- |
| `type` | string enum | yes |
| `justification` | string | yes |
| `tenure` | integer (int32) | no |
| `interestRate` | number | no |
| `repaymentFrequency` | string enum | no |
| `repaymentDayOfMonth` | integer (int32) | no |
| `holidayRule` | string enum | no |
| `revisedInstalment` | number | no |
| `moratoriumDays` | integer (int32) | no |
| `partialSettlementAmount` | number | no |
| `revisedMaturity` | string (date) | no |
| `valueDate` | string (date) | no |

### `RestructuringResponse`

| Property | Type | Required |
| --- | --- | --- |
| `id` | string (uuid) | no |
| `loanAccountId` | string (uuid) | no |
| `type` | string | no |
| `status` | string | no |
| `justification` | string | no |
| `tenure` | integer (int32) | no |
| `interestRate` | number | no |
| `repaymentFrequency` | string | no |
| `moratoriumDays` | integer (int32) | no |
| `partialSettlementAmount` | number | no |
| `revisedInstalment` | number | no |
| `revisedMaturity` | string (date) | no |
| `daysPastDueAtRequest` | integer (int32) | no |
| `bucketAtRequest` | string | no |
| `overdueAtRequest` | number | no |
| `daysPastDueAtDecision` | integer (int32) | no |
| `bucketAtDecision` | string | no |
| `overdueAtDecision` | number | no |
| `outstandingPrincipalBefore` | number | no |
| `outstandingInterestBefore` | number | no |
| `outstandingPrincipalAfter` | number | no |
| `outstandingInterestAfter` | number | no |
| `reconciliationMatched` | boolean | no |
| `reconciliationDifference` | number | no |
| `approval` | `RestructuringApprovalResponse` | no |
| `schedule` | `ScheduleLinkResponse` | no |

### `Result`

| Property | Type | Required |
| --- | --- | --- |
| `id` | string (uuid) | no |
| `screeningType` | string | no |
| `outcome` | string | no |
| `provider` | string | no |
| `detail` | string | no |
| `screenedAt` | string (date-time) | no |

### `ReversalInstructionRequest`

| Property | Type | Required |
| --- | --- | --- |
| `idempotencyKey` | string | yes |

### `ReversalRequest`

| Property | Type | Required |
| --- | --- | --- |
| `idempotencyKey` | string | yes |

### `ReviewCase`

| Property | Type | Required |
| --- | --- | --- |
| `id` | string (uuid) | no |
| `caseType` | string | no |
| `status` | string | no |
| `mandatoryHold` | boolean | no |
| `summary` | string | no |
| `openedAt` | string (date-time) | no |

### `RiskBand`

| Property | Type | Required |
| --- | --- | --- |
| `code` | string | no |
| `minimumScore` | number | no |

### `RoleAssignmentResponse`

| Property | Type | Required |
| --- | --- | --- |
| `user` | `UserResponse` | no |
| `mfaSetup` | `MfaSetup` | no |

### `RoleResponse`

| Property | Type | Required |
| --- | --- | --- |
| `id` | string (uuid) | no |
| `code` | string | no |
| `name` | string | no |
| `description` | string | no |
| `requiresMfa` | boolean | no |
| `unrestrictedBranches` | boolean | no |
| `systemRole` | boolean | no |
| `permissions` | array of string | no |

### `Rules`

| Property | Type | Required |
| --- | --- | --- |
| `par` | string | no |
| `collectionRate` | string | no |
| `defaultRate` | string | no |
| `approvalRate` | string | no |
| `turnaround` | string | no |
| `averageLoanSize` | string | no |
| `disbursement` | string | no |
| `collections` | string | no |
| `security` | string | no |

### `SaveAgreementTemplateRequest`

| Property | Type | Required |
| --- | --- | --- |
| `code` | string | yes |
| `name` | string | yes |
| `kind` | string | yes |
| `body` | string | yes |

### `SaveApprovalMatrixRequest`

| Property | Type | Required |
| --- | --- | --- |
| `code` | string | yes |
| `name` | string | yes |
| `description` | string | no |
| `productId` | string (uuid) | no |
| `customerSegment` | string | no |
| `branchId` | string (uuid) | no |
| `riskRating` | string | no |
| `collateralStatus` | string | no |
| `securityClassification` | string | no |
| `exceptionType` | string | no |
| `automatedAllowed` | boolean | yes |
| `amountMin` | number | yes |
| `amountMax` | number | no |
| `levels` | array of `LevelRequest` | yes |

### `SaveCreditRuleRequest`

| Property | Type | Required |
| --- | --- | --- |
| `code` | string | yes |
| `name` | string | yes |
| `description` | string | yes |
| `segment` | string | yes |
| `productId` | string (uuid) | no |
| `ruleType` | string | yes |
| `mandatory` | boolean | no |
| `exceptionAllowed` | boolean | no |
| `threshold` | number | no |
| `failureOutcome` | string | yes |
| `policyVersion` | string | yes |
| `active` | boolean | no |

### `SaveLoanApplicationRequest`

Creates or updates a loan application draft. React and Flutter send the same JSON body. On update, omit a section to leave it unchanged. Send an empty array to clear obligations, guarantors, or collaterals. Individual and SME financial sections are separate: send only the section that matches the customer.

| Property | Type | Required |
| --- | --- | --- |
| `customerId` | string (uuid) | no |
| `productId` | string (uuid) | no |
| `amount` | number | no |
| `purpose` | string | no |
| `tenure` | integer (int32) | no |
| `repaymentFrequency` | string | no |
| `disbursementAccountId` | string (uuid) | no |
| `individualFinancials` | `IndividualFinancialsRequest` | no |
| `smeFinancials` | `SmeFinancialsRequest` | no |
| `obligations` | array of `ObligationRequest` | no |
| `guarantors` | array of `GuarantorRequest` | no |
| `collaterals` | array of `CollateralRequest` | no |

### `ScheduleItemResponse`

| Property | Type | Required |
| --- | --- | --- |
| `instalmentNumber` | integer (int32) | no |
| `dueDate` | string (date) | no |
| `openingBalance` | number | no |
| `principalDue` | number | no |
| `interestDue` | number | no |
| `instalmentAmount` | number | no |
| `closingBalance` | number | no |

### `ScheduleLineResponse`

| Property | Type | Required |
| --- | --- | --- |
| `number` | integer (int32) | no |
| `contractualDueDate` | string (date) | no |
| `dueDate` | string (date) | no |
| `openingBalance` | number | no |
| `principalDue` | number | no |
| `interestDue` | number | no |
| `instalmentAmount` | number | no |
| `closingBalance` | number | no |
| `principalPaid` | number | no |
| `interestPaid` | number | no |
| `penaltyAccrued` | number | no |
| `status` | string | no |

### `ScheduleLinkResponse`

| Property | Type | Required |
| --- | --- | --- |
| `previousScheduleId` | string (uuid) | no |
| `newScheduleId` | string (uuid) | no |
| `previousVersion` | integer (int32) | no |
| `newVersion` | integer (int32) | no |
| `preserved` | boolean | no |

### `ScheduleResponse`

| Property | Type | Required |
| --- | --- | --- |
| `loanAccountId` | string (uuid) | no |
| `version` | integer (int32) | no |
| `status` | string | no |
| `engineVersion` | string | no |
| `servicingVersion` | string | no |
| `fingerprint` | string | no |
| `parameters` | string | no |
| `reason` | string | no |
| `lines` | array of `ScheduleLineResponse` | no |

### `ScoreComponentResult`

| Property | Type | Required |
| --- | --- | --- |
| `code` | string | no |
| `metric` | string | no |
| `rawValue` | number | no |
| `rawScore` | number | no |
| `weight` | number | no |
| `weightedScore` | number | no |
| `explanation` | string | no |

### `ScoringComponentSpec`

| Property | Type | Required |
| --- | --- | --- |
| `code` | string | no |
| `weight` | number | no |
| `metric` | string | no |
| `direction` | string enum | no |
| `best` | number | no |
| `worst` | number | no |

### `ScoringModelResponse`

| Property | Type | Required |
| --- | --- | --- |
| `id` | string (uuid) | no |
| `code` | string | no |
| `name` | string | no |
| `segment` | string | no |
| `productId` | string (uuid) | no |
| `status` | string | no |
| `currentVersion` | `ScoringVersionResponse` | no |
| `versions` | array of `ScoringVersionResponse` | no |

### `ScoringSpecRequest`

| Property | Type | Required |
| --- | --- | --- |
| `policyVersion` | string | yes |
| `components` | array of `ScoringComponentSpec` | yes |
| `categoricalScores` | object | no |
| `riskCategories` | array of `RiskBand` | yes |
| `decisionThresholds` | `DecisionThresholds` | yes |
| `changeReason` | string | no |

### `ScoringVersionResponse`

| Property | Type | Required |
| --- | --- | --- |
| `id` | string (uuid) | no |
| `versionNumber` | integer (int32) | no |
| `status` | string | no |
| `weights` | `ScoringWeights` | no |
| `riskCategories` | array of `RiskBand` | no |
| `decisionThresholds` | `DecisionThresholds` | no |
| `policyVersion` | string | no |
| `changeReason` | string | no |
| `activatedAt` | string (date-time) | no |

### `ScoringWeights`

| Property | Type | Required |
| --- | --- | --- |
| `components` | array of `ScoringComponentSpec` | no |
| `categoricalScores` | object | no |

### `ScreeningResponse`

| Property | Type | Required |
| --- | --- | --- |
| `customerId` | string (uuid) | no |
| `riskClassification` | string | no |
| `riskScore` | integer (int32) | no |
| `results` | array of `Result` | no |

### `SessionResponse`

| Property | Type | Required |
| --- | --- | --- |
| `id` | string (uuid) | no |
| `status` | string | no |
| `ipAddress` | string | no |
| `userAgent` | string | no |
| `createdAt` | string (date-time) | no |
| `lastSeenAt` | string (date-time) | no |
| `expiresAt` | string (date-time) | no |
| `revokedAt` | string (date-time) | no |
| `revokedReason` | string | no |

### `SettlementQuoteRequest`

| Property | Type | Required |
| --- | --- | --- |
| `valueDate` | string (date) | no |

### `SettlementQuoteResponse`

| Property | Type | Required |
| --- | --- | --- |
| `loanAccountId` | string (uuid) | no |
| `valueDate` | string (date) | no |
| `currencyCode` | string | no |
| `principal` | number | no |
| `accruedInterest` | number | no |
| `unearnedInterest` | number | no |
| `fees` | number | no |
| `penalties` | number | no |
| `earlyFee` | number | no |
| `interestCredit` | number | no |
| `totalDue` | number | no |

### `SettlementRequest`

| Property | Type | Required |
| --- | --- | --- |
| `amount` | number | yes |
| `channel` | string enum | yes |
| `idempotencyKey` | string | yes |
| `valueDate` | string (date) | no |
| `externalReference` | string | no |

### `SmeAssessmentRequest`

| Property | Type | Required |
| --- | --- | --- |
| `monthlyRevenue` | number | no |
| `operatingExpenses` | number | no |
| `netOperatingCashFlow` | number | no |
| `existingLiabilities` | number | no |
| `existingDebtService` | number | no |
| `businessAgeMonths` | integer (int32) | no |
| `accountTurnover` | number | no |
| `businessSector` | string | no |
| `financialStatementsAvailable` | boolean | no |
| `financialStatementsAudited` | boolean | no |

### `SmeFinancials`

| Property | Type | Required |
| --- | --- | --- |
| `annualTurnover` | number | no |
| `monthlyTurnover` | number | no |
| `operatingExpenses` | number | no |
| `monthlyCashFlow` | number | no |
| `businessLiabilities` | number | no |
| `authorisedToBorrow` | boolean | no |
| `authorisedBorrowerName` | string | no |
| `authorisedBorrowingLimit` | number | no |

### `SmeFinancialsRequest`

Business turnover, operating expenses, cash flow, liabilities, and borrowing authority. Use this for SME customers only.

| Property | Type | Required |
| --- | --- | --- |
| `annualTurnover` | number | no |
| `monthlyTurnover` | number | no |
| `operatingExpenses` | number | no |
| `monthlyCashFlow` | number | no |
| `businessLiabilities` | number | no |
| `authorisedToBorrow` | boolean | no |
| `authorisedBorrowerName` | string | no |
| `authorisedBorrowingLimit` | number | no |

### `SmeProfileRequest`

| Property | Type | Required |
| --- | --- | --- |
| `registeredBusinessName` | string | yes |
| `tradingName` | string | no |
| `cacRegistrationNumber` | string | yes |
| `businessType` | string | yes |
| `businessSector` | string | yes |
| `incorporationDate` | string (date) | no |
| `businessAddress` | `AddressRequest` | yes |
| `annualTurnover` | number | yes |
| `monthlyRevenue` | number | yes |
| `operatingExpenses` | number | yes |
| `taxIdentificationNumber` | string | yes |
| `authorisedRepresentatives` | array of `RepresentativeRequest` | yes |
| `consents` | array of `ConsentRequest` | no |

### `SmeProfileResponse`

| Property | Type | Required |
| --- | --- | --- |
| `registeredBusinessName` | string | no |
| `tradingName` | string | no |
| `cacRegistrationNumber` | string | no |
| `businessType` | string | no |
| `businessSector` | string | no |
| `incorporationDate` | string (date) | no |
| `annualTurnover` | number | no |
| `monthlyRevenue` | number | no |
| `operatingExpenses` | number | no |
| `taxIdentificationNumberMasked` | string | no |

### `StatementResponse`

| Property | Type | Required |
| --- | --- | --- |
| `loanAccountId` | string (uuid) | no |
| `from` | string (date) | no |
| `to` | string (date) | no |
| `currencyCode` | string | no |
| `opening` | `BalanceResponse` | no |
| `movements` | array of `BalanceResponse` | no |
| `events` | array of `EventResponse` | no |
| `repayments` | array of `RepaymentResponse` | no |
| `contractualInterest` | number | no |
| `interestPaid` | number | no |
| `unearnedInterest` | number | no |

### `StatementSummary`

| Property | Type | Required |
| --- | --- | --- |
| `principalPaid` | number | no |
| `interestPaid` | number | no |
| `feesPaid` | number | no |
| `penaltiesPaid` | number | no |
| `lastPaymentDate` | string (date) | no |

### `StatusChange`

| Property | Type | Required |
| --- | --- | --- |
| `id` | string (uuid) | no |
| `fromStatus` | string | no |
| `toStatus` | string | no |
| `reason` | string | no |
| `actorId` | string (uuid) | no |
| `occurredAt` | string (date-time) | no |

### `StoredDocumentResponse`

| Property | Type | Required |
| --- | --- | --- |
| `id` | string (uuid) | no |
| `documentCode` | string | no |
| `fileName` | string | no |
| `contentType` | string | no |
| `sizeBytes` | integer (int64) | no |
| `checksumSha256` | string | no |
| `uploadedAt` | string (date-time) | no |

### `SubmitComplaintRequest`

| Property | Type | Required |
| --- | --- | --- |
| `category` | string | yes |
| `subject` | string | yes |
| `description` | string | yes |

### `SystemConfiguration`

| Property | Type | Required |
| --- | --- | --- |
| `id` | string (uuid) | no |
| `key` | string | no |
| `value` | string | no |
| `description` | string | no |
| `valueType` | string | no |
| `sensitive` | boolean | no |
| `masked` | boolean | no |

### `TemplateRequest`

| Property | Type | Required |
| --- | --- | --- |
| `eventType` | string | yes |
| `channel` | string | yes |
| `locale` | string | no |
| `subject` | string | no |
| `body` | string | yes |

### `TemplateResponse`

| Property | Type | Required |
| --- | --- | --- |
| `id` | string (uuid) | no |
| `code` | string | no |
| `channel` | string | no |
| `eventType` | string | no |
| `locale` | string | no |
| `subject` | string | no |
| `body` | string | no |
| `active` | boolean | no |
| `version` | integer (int32) | no |

### `TermsResponse`

| Property | Type | Required |
| --- | --- | --- |
| `productId` | string (uuid) | no |
| `productCode` | string | no |
| `productName` | string | no |
| `productVersionId` | string (uuid) | no |
| `principal` | number | no |
| `currencyCode` | string | no |
| `interestRate` | number | no |
| `interestMethod` | string | no |
| `interestPeriod` | string | no |
| `tenure` | integer (int32) | no |
| `tenureUnit` | string | no |
| `repaymentFrequency` | string | no |
| `dayCountConvention` | string | no |
| `roundingPrecision` | integer (int32) | no |
| `roundingMethod` | string | no |
| `gracePeriodDays` | integer (int32) | no |
| `instalmentAmount` | number | no |
| `processingFees` | number | no |
| `otherFees` | number | no |
| `taxes` | number | no |
| `taxRate` | number | no |
| `totalInterest` | number | no |
| `totalRepayment` | number | no |
| `netDisbursement` | number | no |
| `disbursementDate` | string (date) | no |
| `fees` | array of `FeeResponse` | no |
| `disbursementConditions` | string | no |
| `collateralDetails` | string | no |
| `guarantorDetails` | string | no |
| `defaultProvisions` | string | no |

### `TransitionRequest`

| Property | Type | Required |
| --- | --- | --- |
| `status` | string | yes |
| `reason` | string | no |

### `UpdateCollateralRequest`

| Property | Type | Required |
| --- | --- | --- |
| `description` | string | no |
| `ownerName` | string | no |
| `ownerIdentifier` | string | no |
| `identificationNumber` | string | no |
| `location` | string | no |
| `insurance` | `InsuranceRequest` | no |

### `UpdateCustomerRequest`

Updates the customer header. Omit a field to leave it unchanged. Customers can change their own contact details. Staff can also change category, branch, status, blacklist, and risk classification.

| Property | Type | Required |
| --- | --- | --- |
| `displayName` | string | no |
| `email` | string (email) | no |
| `phoneNumber` | string | no |
| `category` | string | no |
| `branchId` | string (uuid) | no |
| `status` | string | no |
| `blacklistReason` | string | no |
| `riskClassification` | string | no |

### `UpdatePermissionRequest`

| Property | Type | Required |
| --- | --- | --- |
| `description` | string | yes |

### `UpdateRoleRequest`

| Property | Type | Required |
| --- | --- | --- |
| `name` | string | yes |
| `description` | string | no |
| `requiresMfa` | boolean | no |
| `unrestrictedBranches` | boolean | no |
| `permissionCodes` | array of string | no |

### `UpdateUserRequest`

| Property | Type | Required |
| --- | --- | --- |
| `fullName` | string | no |
| `email` | string (email) | no |
| `phoneNumber` | string | no |
| `businessName` | string | no |
| `status` | string | no |
| `branchIds` | array of string (uuid) | no |
| `primaryBranchId` | string (uuid) | no |

### `UserResponse`

| Property | Type | Required |
| --- | --- | --- |
| `id` | string (uuid) | no |
| `principalType` | string | no |
| `customerType` | string | no |
| `fullName` | string | no |
| `businessName` | string | no |
| `email` | string | no |
| `phoneNumber` | string | no |
| `status` | string | no |
| `emailVerified` | boolean | no |
| `phoneVerified` | boolean | no |
| `mfaEnabled` | boolean | no |
| `roles` | array of string | no |
| `permissions` | array of string | no |
| `branchIds` | array of string (uuid) | no |
| `passwordExpiresAt` | string (date-time) | no |

### `ValuationDecisionRequest`

| Property | Type | Required |
| --- | --- | --- |
| `decision` | string enum | yes |
| `reason` | string | no |

### `ValuationResponse`

| Property | Type | Required |
| --- | --- | --- |
| `id` | string (uuid) | no |
| `kind` | string | no |
| `status` | string | no |
| `marketValue` | number | no |
| `forcedSaleValue` | number | no |
| `lendingRatio` | number | no |
| `calculatedEligibleValue` | number | no |
| `acceptedEligibleValue` | number | no |
| `valuationDate` | string (date) | no |
| `expiryDate` | string (date) | no |
| `valuerName` | string | no |
| `valuerReference` | string | no |
| `submittedBy` | string (uuid) | no |
| `decidedBy` | string (uuid) | no |
| `decidedAt` | string (date-time) | no |
| `decisionReason` | string | no |
| `createdAt` | string (date-time) | no |

### `VerifyCollateralRequest`

| Property | Type | Required |
| --- | --- | --- |
| `check` | string enum | yes |
| `outcome` | string enum | no |
| `notes` | string | no |
| `encumbranceHolder` | string | no |
| `encumbranceReference` | string | no |
| `encumbranceAmount` | number | no |

### `VerifyOtpRequest`

| Property | Type | Required |
| --- | --- | --- |
| `identifier` | string | yes |
| `purpose` | string | yes |
| `code` | string | yes |

### `VersionResponse`

| Property | Type | Required |
| --- | --- | --- |
| `name` | string | no |
| `version` | string | no |
| `javaVersion` | string | no |
| `springBootVersion` | string | no |

### `WebhookAcceptedResponse`

| Property | Type | Required |
| --- | --- | --- |
| `duplicate` | boolean | no |
| `transactionId` | string (uuid) | no |
| `state` | string | no |

### `WithdrawRequest`

| Property | Type | Required |
| --- | --- | --- |
| `reason` | string | yes |

### `WriteOffReport`

| Property | Type | Required |
| --- | --- | --- |
| `accountCode` | string | no |
| `generalLedgerExpense` | number | no |
| `uncollectibleCases` | integer (int64) | no |
| `uncollectibleExposure` | number | no |
| `recoveryAmount` | number | no |
| `linkedPostedRepayment` | number | no |
| `recoveryVariance` | number | no |
| `recoveryCount` | integer (int64) | no |
| `unpostedRecoveries` | integer (int64) | no |
| `rule` | string | no |
| `lines` | `DetailPage` | no |

## Monetary properties

These properties are JSON numbers. They are not decimal strings.

| Schema | Property | JSON type |
| --- | --- | --- |
| `AllocateCollateralRequest` | `allocatedAmount` | number |
| `AllocationResponse` | `amount` | number |
| `AllocationSummaryResponse` | `allocatedAmount` | number |
| `ApplicationDashboardResponse` | `requestedAmount` | number |
| `ApprovalHistoryResponse` | `amount` | number |
| `ApprovalLevelResponse` | `minAmount` | number |
| `ApprovalLevelResponse` | `maxAmount` | number |
| `AssessmentFacts` | `monthlyGrossIncome` | number |
| `AssessmentFacts` | `netIncome` | number |
| `AssessmentFacts` | `monthlyExpenses` | number |
| `AssessmentFacts` | `bankAccountTurnover` | number |
| `AssessmentFacts` | `operatingExpenses` | number |
| `AssessmentFacts` | `netOperatingCashFlow` | number |
| `AssessmentFacts` | `existingLiabilities` | number |
| `AssessmentFacts` | `accountTurnover` | number |
| `BalanceResponse` | `outstandingPrincipal` | number |
| `BalanceResponse` | `accruedInterest` | number |
| `BalanceResponse` | `outstandingInterest` | number |
| `BalanceResponse` | `outstandingFees` | number |
| `BalanceResponse` | `outstandingPenalties` | number |
| `BalanceResponse` | `totalOutstanding` | number |
| `CalculatorConfigResponse` | `minimumAmount` | number |
| `CalculatorConfigResponse` | `maximumAmount` | number |
| `CalculatorConfigResponse` | `interestRate` | number |
| `CalculatorConfigResponse` | `earlyRepaymentFeeRate` | number |
| `CalculatorConfigResponse` | `penaltyRate` | number |
| `CalculatorConfigResponse` | `penaltyAmount` | number |
| `CaseResponse` | `overdueAmount` | number |
| `CloseRequest` | `recoveryAmount` | number |
| `CollateralResponse` | `allocatedAmount` | number |
| `CollateralResponse` | `availableAmount` | number |
| `CollectionsDashboard` | `outstandingPrincipal` | number |
| `CollectionsDashboard` | `overduePrincipal` | number |
| `CollectionsDashboard` | `par30` | number |
| `CollectionsDashboard` | `par60` | number |
| `CollectionsDashboard` | `par90` | number |
| `CollectionsDashboard` | `par30Principal` | number |
| `CollectionsDashboard` | `par60Principal` | number |
| `CollectionsDashboard` | `par90Principal` | number |
| `CollectionsDashboard` | `defaultRate` | number |
| `CollectionsDashboard` | `nonPerformingPrincipal` | number |
| `CollectionsDashboard` | `postedRepaymentCash` | number |
| `CollectionsDashboard` | `collectionRate` | number |
| `CollectionsDashboard` | `recoveryAmount` | number |
| `CollectionsReportResponse` | `recoveredAmount` | number |
| `CommercialTermsRequest` | `minimumAmount` | number |
| `CommercialTermsRequest` | `maximumAmount` | number |
| `CommercialTermsRequest` | `interestRate` | number |
| `CommercialTermsRequest` | `earlyRepaymentFeeRate` | number |
| `CommercialTermsRequest` | `penaltyRate` | number |
| `CommercialTermsRequest` | `penaltyAmount` | number |
| `CoreTransactionResponse` | `amount` | number |
| `CoreTransactionResponse` | `appliedAmount` | number |
| `CreateCreditAssessmentRequest` | `bankAccountTurnover` | number |
| `CreateGuarantorRequest` | `monthlyIncome` | number |
| `CreditDashboard` | `approvalRate` | number |
| `CreditDashboard` | `rejectionRate` | number |
| `CustomerApplication` | `amount` | number |
| `CustomerDashboard` | `outstandingPrincipal` | number |
| `CustomerDashboard` | `outstandingInterest` | number |
| `CustomerDashboard` | `outstandingFees` | number |
| `CustomerDashboard` | `outstandingPenalties` | number |
| `CustomerDashboard` | `nextRepaymentAmount` | number |
| `CustomerLoan` | `outstandingPrincipal` | number |
| `CustomerLoan` | `outstandingInterest` | number |
| `CustomerLoan` | `outstandingFees` | number |
| `CustomerLoan` | `outstandingPenalties` | number |
| `CustomerLoan` | `nextDueAmount` | number |
| `CustomerRepayment` | `amount` | number |
| `DefaultReport` | `defaultRate` | number |
| `DefaultReport` | `nonPerformingPrincipal` | number |
| `DelegationResponse` | `maxAmount` | number |
| `DelinquencyReport` | `overduePrincipal` | number |
| `DisburseInstructionRequest` | `amount` | number |
| `EncumbranceResponse` | `amount` | number |
| `FeeRequest` | `amount` | number |
| `FeeRequest` | `rate` | number |
| `FeeResponse` | `amount` | number |
| `FeeResponse` | `rate` | number |
| `GenerateOfferRequest` | `principal` | number |
| `GrantDelegationRequest` | `maxAmount` | number |
| `GroupRow` | `outstandingPrincipal` | number |
| `GroupRow` | `overduePrincipal` | number |
| `GroupRow` | `par30` | number |
| `GroupRow` | `par60` | number |
| `GroupRow` | `par90` | number |
| `GroupRow` | `disbursementPrincipal` | number |
| `GroupRow` | `collectionCash` | number |
| `GroupRow` | `income` | number |
| `GuarantorResponse` | `monthlyIncome` | number |
| `IndividualAssessmentRequest` | `monthlyGrossIncome` | number |
| `IndividualAssessmentRequest` | `netIncome` | number |
| `IndividualAssessmentRequest` | `monthlyExpenses` | number |
| `IndividualFinancials` | `monthlyIncome` | number |
| `IndividualFinancials` | `otherIncome` | number |
| `IndividualFinancials` | `monthlyExpenses` | number |
| `IndividualFinancialsRequest` | `monthlyIncome` | number |
| `IndividualFinancialsRequest` | `otherIncome` | number |
| `IndividualFinancialsRequest` | `monthlyExpenses` | number |
| `IndividualProfileRequest` | `monthlyIncome` | number |
| `IndividualProfileRequest` | `monthlyExpenses` | number |
| `IndividualProfileResponse` | `monthlyIncome` | number |
| `IndividualProfileResponse` | `monthlyExpenses` | number |
| `InsuranceRequest` | `coverAmount` | number |
| `InsuranceResponse` | `coverAmount` | number |
| `InterestAdjustmentRequest` | `amount` | number |
| `JournalLineResponse` | `amount` | number |
| `LevelRequest` | `minAmount` | number |
| `LevelRequest` | `maxAmount` | number |
| `LoanAccountResponse` | `originalPrincipal` | number |
| `LoanAccountResponse` | `outstandingPrincipal` | number |
| `LoanAccountResponse` | `accruedInterest` | number |
| `LoanAccountResponse` | `outstandingInterest` | number |
| `LoanAccountResponse` | `outstandingFees` | number |
| `LoanAccountResponse` | `outstandingPenalties` | number |
| `LoanAccountResponse` | `totalOutstanding` | number |
| `LoanAccountResponse` | `interestRate` | number |
| `LoanApplicationResponse` | `amount` | number |
| `LoanApplicationSummaryResponse` | `amount` | number |
| `LoanCollateralListResponse` | `facilityAmount` | number |
| `LoanCollateralListResponse` | `coverageRatio` | number |
| `LoanCollateralResponse` | `allocatedAmount` | number |
| `LoanCollateralResponse` | `availableAmount` | number |
| `LoanCollateralResponse` | `coverageRatio` | number |
| `LoanLine` | `amount` | number |
| `LoanLine` | `principal` | number |
| `LoanLine` | `interest` | number |
| `LoanLine` | `fees` | number |
| `LoanOfferSummaryResponse` | `principal` | number |
| `LoanOfferSummaryResponse` | `instalmentAmount` | number |
| `LoanProductSummaryResponse` | `minimumAmount` | number |
| `LoanProductSummaryResponse` | `maximumAmount` | number |
| `LoanProductSummaryResponse` | `interestRate` | number |
| `LoanProductVersionResponse` | `minimumAmount` | number |
| `LoanProductVersionResponse` | `maximumAmount` | number |
| `LoanProductVersionResponse` | `interestRate` | number |
| `LoanProductVersionResponse` | `earlyRepaymentFeeRate` | number |
| `LoanProductVersionResponse` | `penaltyRate` | number |
| `LoanProductVersionResponse` | `penaltyAmount` | number |
| `MatrixVersionResponse` | `amountMin` | number |
| `MatrixVersionResponse` | `amountMax` | number |
| `ObligationItem` | `outstandingBalance` | number |
| `ObligationItem` | `monthlyRepayment` | number |
| `ObligationRequest` | `outstandingBalance` | number |
| `ObligationRequest` | `monthlyRepayment` | number |
| `OfficerPerformanceResponse` | `recoveredAmount` | number |
| `OperationsDashboard` | `disbursementPrincipal` | number |
| `OperationsDashboard` | `outstandingPrincipal` | number |
| `OperationsDashboard` | `outstandingInterest` | number |
| `OperationsDashboard` | `overduePrincipal` | number |
| `OperationsDashboard` | `postedRepaymentCash` | number |
| `OperationsDashboard` | `collectionRate` | number |
| `ParBandResponse` | `outstandingPrincipal` | number |
| `ParResponse` | `denominatorOutstandingPrincipal` | number |
| `ParSummary` | `par30` | number |
| `ParSummary` | `par60` | number |
| `ParSummary` | `par90` | number |
| `ParSummary` | `par30Principal` | number |
| `ParSummary` | `par60Principal` | number |
| `ParSummary` | `par90Principal` | number |
| `PortfolioReport` | `totalDisbursements` | number |
| `PortfolioReport` | `outstandingPrincipal` | number |
| `PortfolioReport` | `outstandingInterest` | number |
| `PortfolioReport` | `totalRepaymentCollections` | number |
| `PortfolioReport` | `collectionRate` | number |
| `PortfolioReport` | `par30` | number |
| `PortfolioReport` | `par60` | number |
| `PortfolioReport` | `par90` | number |
| `PortfolioReport` | `par30Principal` | number |
| `PortfolioReport` | `par60Principal` | number |
| `PortfolioReport` | `par90Principal` | number |
| `PortfolioReport` | `defaultRate` | number |
| `PortfolioReport` | `nonPerformingPrincipal` | number |
| `PortfolioReport` | `approvalRate` | number |
| `PortfolioReport` | `rejectionRate` | number |
| `PromiseRequest` | `amount` | number |
| `PromiseResponse` | `amount` | number |
| `ReconciliationResponse` | `expectedAmount` | number |
| `ReconciliationResponse` | `actualAmount` | number |
| `RecoveryRequest` | `amount` | number |
| `RecoveryResponse` | `amount` | number |
| `RepaymentInstructionRequest` | `amount` | number |
| `RepaymentRequest` | `amount` | number |
| `RepaymentResponse` | `amount` | number |
| `RepaymentResponse` | `feeCharged` | number |
| `RestructureRequest` | `interestRate` | number |
| `RestructuringRequest` | `interestRate` | number |
| `RestructuringRequest` | `partialSettlementAmount` | number |
| `RestructuringResponse` | `interestRate` | number |
| `RestructuringResponse` | `partialSettlementAmount` | number |
| `RestructuringResponse` | `overdueAtRequest` | number |
| `RestructuringResponse` | `overdueAtDecision` | number |
| `RestructuringResponse` | `outstandingPrincipalBefore` | number |
| `RestructuringResponse` | `outstandingInterestBefore` | number |
| `RestructuringResponse` | `outstandingPrincipalAfter` | number |
| `RestructuringResponse` | `outstandingInterestAfter` | number |
| `SaveApprovalMatrixRequest` | `amountMin` | number |
| `SaveApprovalMatrixRequest` | `amountMax` | number |
| `SaveLoanApplicationRequest` | `amount` | number |
| `ScheduleItemResponse` | `openingBalance` | number |
| `ScheduleItemResponse` | `principalDue` | number |
| `ScheduleItemResponse` | `interestDue` | number |
| `ScheduleItemResponse` | `instalmentAmount` | number |
| `ScheduleItemResponse` | `closingBalance` | number |
| `ScheduleLineResponse` | `openingBalance` | number |
| `ScheduleLineResponse` | `principalDue` | number |
| `ScheduleLineResponse` | `interestDue` | number |
| `ScheduleLineResponse` | `instalmentAmount` | number |
| `ScheduleLineResponse` | `closingBalance` | number |
| `ScheduleLineResponse` | `principalPaid` | number |
| `ScheduleLineResponse` | `interestPaid` | number |
| `ScheduleLineResponse` | `penaltyAccrued` | number |
| `SettlementQuoteResponse` | `principal` | number |
| `SettlementQuoteResponse` | `accruedInterest` | number |
| `SettlementQuoteResponse` | `unearnedInterest` | number |
| `SettlementQuoteResponse` | `fees` | number |
| `SettlementQuoteResponse` | `earlyFee` | number |
| `SettlementQuoteResponse` | `interestCredit` | number |
| `SettlementRequest` | `amount` | number |
| `SmeAssessmentRequest` | `operatingExpenses` | number |
| `SmeAssessmentRequest` | `netOperatingCashFlow` | number |
| `SmeAssessmentRequest` | `existingLiabilities` | number |
| `SmeAssessmentRequest` | `accountTurnover` | number |
| `SmeFinancials` | `annualTurnover` | number |
| `SmeFinancials` | `monthlyTurnover` | number |
| `SmeFinancials` | `operatingExpenses` | number |
| `SmeFinancials` | `monthlyCashFlow` | number |
| `SmeFinancials` | `businessLiabilities` | number |
| `SmeFinancialsRequest` | `annualTurnover` | number |
| `SmeFinancialsRequest` | `monthlyTurnover` | number |
| `SmeFinancialsRequest` | `operatingExpenses` | number |
| `SmeFinancialsRequest` | `monthlyCashFlow` | number |
| `SmeFinancialsRequest` | `businessLiabilities` | number |
| `SmeProfileRequest` | `annualTurnover` | number |
| `SmeProfileRequest` | `operatingExpenses` | number |
| `SmeProfileResponse` | `annualTurnover` | number |
| `SmeProfileResponse` | `operatingExpenses` | number |
| `StatementResponse` | `contractualInterest` | number |
| `StatementResponse` | `interestPaid` | number |
| `StatementResponse` | `unearnedInterest` | number |
| `StatementSummary` | `principalPaid` | number |
| `StatementSummary` | `interestPaid` | number |
| `StatementSummary` | `feesPaid` | number |
| `TermsResponse` | `principal` | number |
| `TermsResponse` | `interestRate` | number |
| `TermsResponse` | `instalmentAmount` | number |
| `TermsResponse` | `processingFees` | number |
| `TermsResponse` | `otherFees` | number |
| `TermsResponse` | `taxes` | number |
| `TermsResponse` | `taxRate` | number |
| `TermsResponse` | `totalInterest` | number |
| `TermsResponse` | `totalRepayment` | number |
| `TermsResponse` | `netDisbursement` | number |
| `VerifyCollateralRequest` | `encumbranceAmount` | number |
| `WriteOffReport` | `generalLedgerExpense` | number |
| `WriteOffReport` | `recoveryAmount` | number |
| `WriteOffReport` | `linkedPostedRepayment` | number |


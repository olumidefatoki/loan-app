# Error code catalogue

Every unsuccessful JSON response uses `ApiError`:

```json
{
  "success": false,
  "code": "VALIDATION_ERROR",
  "message": "Request validation failed",
  "errors": [
    {"field": "amount", "message": "must be greater than or equal to 0.01"}
  ],
  "correlationId": "4f3c2a10-6b1e-4d2a-9c11-0a9b8c7d6e5f",
  "timestamp": "2026-11-24T13:00:00Z",
  "path": "/api/v1/repayments"
}
```

`code` is the `ErrorCode` enum name. `errors` is empty when the failure is not a field violation. Clients branch on `success` and then on `code`. The success envelope has `data` and does not have `errors` or `path`.

| Code | HTTP status | When it is returned |
| --- | --- | --- |
| `OK` | 200, 201, 202 | Success envelope only. It is not an error. |
| `VALIDATION_ERROR` | 400 | Bean validation or a named field violation. `errors` lists the fields. |
| `BAD_REQUEST` | 400, 405, 415 | The request is malformed, the method is not allowed, or the media type is unsupported. |
| `DOCUMENT_REJECTED` | 400 | The upload is missing, unreadable, or not an allowed PDF, JPEG, or PNG. |
| `MALWARE_DETECTED` | 400 | The malware scanner rejected the upload. |
| `UNAUTHORIZED` | 401 | The access token is missing or not valid. |
| `TOKEN_EXPIRED` | 401 | The access token is expired. Refresh it. Do not treat this as a logout. |
| `INVALID_CREDENTIALS` | 401 | The identifier or password does not match. |
| `OTP_INVALID` | 401 | The one-time code is wrong. |
| `OTP_EXPIRED` | 401 | The one-time code has expired. |
| `OTP_REUSED` | 401 | The one-time code was already consumed. |
| `MFA_INVALID` | 401 | The staff authenticator code is wrong. |
| `FORBIDDEN` | 403 | The token is valid and the action is not allowed. A customer asking for another customer's resource often receives `RESOURCE_NOT_FOUND` instead. |
| `ACCOUNT_DISABLED` | 403 | The account is disabled. |
| `ACCOUNT_NOT_VERIFIED` | 403 | Email or phone verification is still required. |
| `PASSWORD_EXPIRED` | 403 | The password must be reset before sign-in completes. |
| `MFA_ENROLLMENT_REQUIRED` | 403 | Staff MFA is not enrolled. |
| `ACCOUNT_LOCKED` | 423 | Too many failed sign-in attempts. |
| `RESOURCE_NOT_FOUND` | 404 | The identifier does not exist, or it is outside the caller's scope. |
| `CONFLICT` | 409 | The current state does not allow the action, including a duplicate key. |
| `DUPLICATE_CUSTOMER` | 409 | The user is already linked to a customer. |
| `COMPLIANCE_HOLD` | 409 | A blacklist or open compliance hold blocks the action. |
| `TOKEN_REVOKED` | 409 | The refresh token or session was revoked. |
| `TOKEN_REUSE` | 409 | A rotated refresh token was presented again. The session family is revoked. |
| `RATE_LIMITED` | 429 | The attempt window is exhausted. |
| `SERVICE_UNAVAILABLE` | 503 | A dependency needed to enforce the limit or complete the call is unavailable. |
| `DATABASE_ERROR` | 500 | The database rejected the request. The client message is generic. |
| `INTERNAL_ERROR` | 500 | An unexpected failure. The client message is generic. |

File downloads and the agreement PDF return the same JSON envelope when they fail. A successful download is raw bytes and has no envelope.

Several business rules share `CONFLICT` or `VALIDATION_ERROR`. The `message` and `errors` explain the rule. Adding a new code is backward compatible. Renaming a code is not.

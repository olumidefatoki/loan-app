# Error code catalogue

Every failed request returns `ApiError`. `code` is the `ErrorCode` name. Clients branch on `code`, not on the English `message`.

```json
{
  "success": false,
  "code": "VALIDATION_ERROR",
  "message": "Request validation failed",
  "errors": [{ "field": "identifier", "message": "must not be blank" }],
  "correlationId": "d6aab388-1d8c-4fb1-8a0d-abfe0d1ac2a8",
  "timestamp": "2026-11-24T13:01:43.370065Z",
  "path": "/api/v1/auth/login"
}
```

The validation and unauthenticated examples in `examples/` were captured from the local API. Field failures use `errors[]`. Other failures return `errors` as an empty array.

Success responses are a different shape: `success`, `code` (`OK`), `message`, `data`, `correlationId`, and `timestamp`. They do not include `path` or `errors`. Null properties are omitted.

`X-Correlation-Id` is optional on the request. The response header and `correlationId` carry the id that was used. Unsafe header values are replaced with a UUID.

## Codes

| Code | HTTP | When the client should act |
| --- | --- | --- |
| `OK` | 200, 201, or 202 | Success. `data` holds the payload. |
| `VALIDATION_ERROR` | 400 | Show `errors[]` next to the fields. Also used for an invalid `X-Client-Channel`. |
| `BAD_REQUEST` | 400, 405, or 415 | The request is malformed, the method is not allowed, or the media type is unsupported. |
| `DOCUMENT_REJECTED` | 400 | The upload is empty, unreadable, over 10 MB, or not a PDF, JPEG, or PNG whose bytes match the declared type. |
| `MALWARE_DETECTED` | 400 | The scanner rejected the file. Do not retry the same bytes. |
| `UNAUTHORIZED` | 401 | No token, a bad token, a bad webhook signature, or a refresh token that does not exist. |
| `INVALID_CREDENTIALS` | 401 | Login identifier or password is wrong. The message does not reveal which one. |
| `TOKEN_EXPIRED` | 401 | Access token or refresh token has expired. Refresh once when the access token expires. If the refresh token expires, sign in again. |
| `TOKEN_REVOKED` | 401 | The session or refresh token is no longer active, including a refresh token sent to the wrong principal endpoint. |
| `TOKEN_REUSE` | 401 | A rotated refresh token was presented again. The session family is revoked. Discard both tokens and sign in again. |
| `OTP_INVALID` | 401 | The verification code is wrong. |
| `OTP_EXPIRED` | 401 | Request a new code. Codes last 5 minutes. |
| `OTP_REUSED` | 401 | The code was already used. |
| `MFA_INVALID` | 401 | The authenticator code is wrong. |
| `FORBIDDEN` | 403 | The caller is signed in and lacks the permission or branch scope. |
| `ACCOUNT_DISABLED` | 403 | The account is disabled. |
| `ACCOUNT_NOT_VERIFIED` | 403 | Verify email and phone before customer login. |
| `PASSWORD_EXPIRED` | 403 | Staff password is older than 90 days. Reset it through the password-reset endpoints, then sign in. |
| `MFA_ENROLLMENT_REQUIRED` | 403 | A privileged staff account has no authenticator yet. An administrator must enroll MFA. |
| `RESOURCE_NOT_FOUND` | 404 | The id does not exist, or the resource is outside the caller's scope. Customers receive this, rather than 403, for another customer's loan. |
| `CONFLICT` | 409 | The state or a unique key does not allow the action. |
| `DUPLICATE_CUSTOMER` | 409 | Email, phone, BVN, NIN, CAC, TIN, or an existing user link matches a customer. |
| `COMPLIANCE_HOLD` | 409 | Blacklist, sanctions, PEP, AML, or an open compliance hold blocks the action. |
| `ACCOUNT_LOCKED` | 423 | Five failed logins lock the account for 15 minutes. |
| `RATE_LIMITED` | 429 | Login, registration, reset, or the general API limit was exceeded. |
| `SERVICE_UNAVAILABLE` | 503 | Core banking or the rate-limit store is unavailable. A financial instruction that timed out stays traceable and is not retried blindly. |
| `DATABASE_ERROR` | 500 | Persistence failed. The message does not include SQL. |
| `INTERNAL_ERROR` | 500 | Unexpected failure, or a stored file that cannot be read. |

Spring MVC's fallback mapping uses the same envelope. A missing route is `RESOURCE_NOT_FOUND`. A duplicate database key that is not mapped to a domain code is `CONFLICT`.

File downloads and the agreement PDF return raw bytes on success. Their failures still use this JSON envelope.

# Authentication integration guide

Customer and staff clients use the same token body. The paths are separate so a customer refresh token cannot rotate a staff session, and the reverse.

| Action | Customer | Staff |
| --- | --- | --- |
| Register | `POST /api/v1/auth/register` | `POST /api/v1/admin/users` by an administrator |
| Verify code | `POST /api/v1/auth/verify-otp` | Not used at sign-in |
| Sign in | `POST /api/v1/auth/login` | `POST /api/v1/admin/auth/login` |
| MFA | None | `POST /api/v1/admin/auth/mfa/verify` when `mfaRequired` is true |
| Refresh | `POST /api/v1/auth/refresh-token` | `POST /api/v1/admin/auth/refresh-token` |
| Sign out | `POST /api/v1/auth/logout` | `POST /api/v1/admin/auth/logout` |
| Current identity | `GET /api/v1/auth/me` | `GET /api/v1/auth/me` |
| Forgotten password | `POST /api/v1/auth/forgot-password` | Same customer routes. There is no `/api/v1/admin/auth/forgot-password`. |
| Reset password | `POST /api/v1/auth/reset-password` | Same customer routes |

Public routes do not take `Authorization`. Logout and `/me` do.

## Login

```json
POST /api/v1/auth/login
{
  "identifier": "ada@example.com",
  "password": "Str0ng-Passw0rd!"
}
```

`identifier` is the email address or the phone number in E.164 form, such as `+2348012345678`.

```json
{
  "success": true,
  "code": "OK",
  "message": "Success",
  "data": {
    "tokenType": "Bearer",
    "accessToken": "eyJhbGciOiJIUzI1NiJ9.access",
    "refreshToken": "refresh-token-value",
    "expiresIn": 900,
    "sessionId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
    "mfaRequired": false
  },
  "correlationId": "4f3c2a10-6b1e-4d2a-9c11-0a9b8c7d6e5f",
  "timestamp": "2026-11-24T13:00:00Z"
}
```

Staff login can return `mfaRequired: true` and `mfaToken` without an access token. Complete MFA before calling the API:

```json
POST /api/v1/admin/auth/mfa/verify
{
  "mfaToken": "mfa-token-value",
  "code": "123456"
}
```

The code is six digits. A successful MFA response is the same token body with `mfaRequired` false.

## Refresh

Send the refresh token in JSON. Do not send it as a cookie or as a bearer token.

```json
POST /api/v1/auth/refresh-token
{
  "refreshToken": "refresh-token-value"
}
```

The response replaces both tokens. Discard the previous refresh token. Presenting a rotated refresh token again returns `TOKEN_REUSE` and revokes that session family. A revoked token returns `TOKEN_REVOKED`.

Refresh when the access token is expired or about to expire. The refresh routes are public, so an expired access token does not block them. Serialize refreshes so two tabs or two isolates do not rotate the same token twice.

Web clients should keep the refresh token in memory for a session tab, or in a secure store the deployment provides. Do not put it in `localStorage`. Mobile clients should use the platform secure store.

## Registration and recovery

```json
POST /api/v1/auth/register
{
  "customerType": "INDIVIDUAL",
  "fullName": "Ada Okonkwo",
  "email": "ada@example.com",
  "phoneNumber": "+2348012345678",
  "password": "Str0ng-Passw0rd!"
}
```

SME registration also sends `businessName`. The password length is 12 to 128 characters. The response lists the verifications still required. Confirm each channel:

```json
POST /api/v1/auth/verify-otp
{
  "identifier": "ada@example.com",
  "purpose": "EMAIL_VERIFICATION",
  "code": "123456"
}
```

`purpose` is `EMAIL_VERIFICATION`, `PHONE_VERIFICATION`, or `PASSWORD_RESET`. The code is 6 to 8 digits.

Forgotten password always returns the same message, whether or not the account exists. Reset then consumes the `PASSWORD_RESET` code and revokes existing sessions.

```json
POST /api/v1/auth/reset-password
{
  "identifier": "ada@example.com",
  "code": "123456",
  "newPassword": "N3w-Str0ng-Passw0rd!"
}
```

There is no authenticated change-password route. A signed-in user who wants a new password uses this recovery flow.

## Failures

| Situation | Code | Client action |
| --- | --- | --- |
| Wrong password | `INVALID_CREDENTIALS` | Show the generic message. |
| Too many attempts | `ACCOUNT_LOCKED` | Stop and show the lock message. HTTP 423. |
| Expired access token | `TOKEN_EXPIRED` | Refresh once and retry. |
| Reused refresh token | `TOKEN_REUSE` | Clear the session. |
| Unverified customer | `ACCOUNT_NOT_VERIFIED` | Return to OTP. |
| Staff MFA still required | `MFA_ENROLLMENT_REQUIRED` or `mfaRequired` | Continue the MFA step. |
| Too many OTP or login calls | `RATE_LIMITED` | Wait. HTTP 429. |

Sign-out revokes the current session and its refresh tokens. It requires the access token.

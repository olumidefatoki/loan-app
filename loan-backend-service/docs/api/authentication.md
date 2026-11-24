# Authentication integration

Customer and staff clients share one token format. Send the access token as `Authorization: Bearer <accessToken>`. Send the refresh token in JSON. The API does not set cookies.

Access tokens last 15 minutes. Customer refresh tokens last 7 days. Staff refresh tokens last 12 hours. A staff MFA challenge lasts 5 minutes. OTP codes are 6 digits and last 5 minutes.

## Customer

| Step | Request |
| --- | --- |
| Register | `POST /api/v1/auth/register` returns 201. `customerType` is `INDIVIDUAL` or `SME`. Phone is E.164, such as `+2348012345678`. Password is 12 to 128 characters and must include an uppercase letter, a lowercase letter, a digit, and a symbol. It must not contain the email local-part or the phone number. |
| Verify email or phone | `POST /api/v1/auth/verify-otp` with `purpose` of `EMAIL_VERIFICATION` or `PHONE_VERIFICATION`. Login stays `ACCOUNT_NOT_VERIFIED` until both are done. |
| Sign in | `POST /api/v1/auth/login`. The identifier is the email or phone. |
| Current user | `GET /api/v1/auth/me`. |
| Sign out | `POST /api/v1/auth/logout` with the bearer token. This revokes the session and its refresh tokens. |
| Forgot password | `POST /api/v1/auth/forgot-password`. The response is the same whether the account exists. |
| Reset password | `POST /api/v1/auth/reset-password` with the same code. Do not call `verify-otp` first. That call consumes the code, and reset then returns `OTP_REUSED`. Reset revokes every session. |

Registration returns `userId`, `status` (`PENDING_VERIFICATION`), and `verificationRequired` (`EMAIL` and `PHONE`).

## Staff

| Step | Request |
| --- | --- |
| Sign in | `POST /api/v1/admin/auth/login`. |
| MFA | When `mfaRequired` is true, the access and refresh tokens are omitted. Post `mfaToken` and the authenticator `code` to `POST /api/v1/admin/auth/mfa/verify`. |
| Refresh | `POST /api/v1/admin/auth/refresh-token`. |
| Sign out | `POST /api/v1/admin/auth/logout`. |
| Current user | `GET /api/v1/auth/me`. There is no separate staff profile path. |

A privileged role that has no authenticator receives `403 MFA_ENROLLMENT_REQUIRED`. An expired staff password receives `403 PASSWORD_EXPIRED`.

Staff password reset uses the customer endpoints `POST /api/v1/auth/forgot-password` and `POST /api/v1/auth/reset-password`. The service accepts a staff account on those paths. There is no `/api/v1/admin/auth/forgot-password`.

There is no signed-in "change my password" endpoint. A signed-in user who still knows the current password uses the OTP reset flow, which also revokes other sessions.

## Refresh

Call the refresh URL that matches the principal:

- Customer: `POST /api/v1/auth/refresh-token`
- Staff: `POST /api/v1/admin/auth/refresh-token`

```json
{ "refreshToken": "example-refresh-token" }
```

The response replaces both tokens. Store the new refresh token before retrying the original call. Presenting a replaced refresh token returns `401 TOKEN_REUSE` and revokes that session family. Discard the tokens and require sign-in.

A customer refresh token sent to the staff URL, or the reverse, returns `401 TOKEN_REVOKED`.

On `401 TOKEN_EXPIRED` from a business call, refresh once and retry the call once. On `TOKEN_REUSE`, `TOKEN_REVOKED`, or a failed refresh, clear the session. A second parallel refresh of the same token looks like reuse, so serialize refresh in the client.

## What to store

React has no httpOnly cookie from this API. Keep the access token in memory. Keep the refresh token in a store the team accepts for a banking session, and assume any script that can read the page can read it. Send `X-Correlation-Id` from the client so a failed call can be traced.

Flutter should keep both tokens in secure storage. Attach the access token in an interceptor and refresh under a lock.

Five failed passwords lock the account for 15 minutes (`423 ACCOUNT_LOCKED`). Login and registration also have their own rate limits (`429 RATE_LIMITED`).

Illustrative payloads are in `examples/login.request.json`, `examples/login.response.json`, `examples/refresh.request.json`, and `examples/refresh.response.json`. The token strings there are not live credentials.

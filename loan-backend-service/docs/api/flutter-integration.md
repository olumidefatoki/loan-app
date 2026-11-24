# Flutter integration

Flutter uses the same paths, status codes, error codes, pagination, and money representation as React. Read `react-integration.md` for the screen-to-endpoint map and `authentication.md` for refresh. This guide covers only what is different on a device.

## Client

Generate a Dart client from `openapi.json` with OpenAPI Generator 7 and the `dart-dio` generator, or hand-write a `Dio` client against the catalogue. Set the base URL from flavor configuration.

Store the access token and the refresh token in `flutter_secure_storage`. Refresh inside a single-flight interceptor. Customer builds call `POST /api/v1/auth/refresh-token`. A staff build, if one is shipped, calls `POST /api/v1/admin/auth/refresh-token`. Do not point a customer token at the staff URL.

Send `X-Correlation-Id` as a UUID per user action. Send `X-Client-Channel: MOBILE` on:

- `POST /api/v1/customers`
- `POST /api/v1/customers/{id}/individual-profile`
- `POST /api/v1/customers/{id}/sme-profile`
- `POST /api/v1/loan-applications`

Any other value returns `VALIDATION_ERROR`. Other endpoints ignore the header. The generated OpenAPI document lists the header on the customer tag. It is also valid on loan-application creation, which is where the application record stores the channel.

## Money, dates, and pages

Decode amounts with a decimal type. `jsonDecode` uses `double`. The service sends JSON numbers, including values with a fractional part, and the currency code is `NGN` where the schema has `currency` or `currencyCode`.

Timestamps parse with `DateTime.parse`. Dates without a time are calendar dates, not instants.

Page through `page` and `size`. Read `total`, and read the row list from `items`, `loans`, `repayments`, or `lines` as described in the React guide.

## Device behavior

Uploads follow `file-uploads.md`. Convert HEIC and WebP to JPEG before calling the API. Use the part name `file`.

Push delivery has no device-token API. The contact lookup always supplies a null push token, so a push notification is skipped. In-app notifications from `GET /api/v1/notifications` work without a device token. SMS and email use the customer phone and email already stored. Preferences are `GET` and `PUT /api/v1/customers/me/notification-preferences`. Verification codes cannot be turned off.

Background the app around the 15-minute access-token lifetime. Refresh on the next API call rather than on a timer that can race.

Agreement PDFs and customer documents are raw bytes. Write them to a temporary file and open them with the platform viewer. Do not decode them as the JSON envelope.

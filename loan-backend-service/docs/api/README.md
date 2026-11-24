# Lending API contract

This directory is the client contract for the React web application and the Flutter mobile application. Both clients use the same paths, JSON, and bearer tokens. There is no separate mobile API.

The specification was exported from the running service at `GET /v3/api-docs/v1`, then annotated with the error envelope, create and accept status codes, and file-download media types that the controllers already return. Request and response schemas were not redesigned.

| Document | Use |
| --- | --- |
| [openapi.json](openapi.json) | OpenAPI 3.1. Import into Postman, or generate a TypeScript or Dart client. |
| [endpoint-catalogue.md](endpoint-catalogue.md) | All 258 operations, grouped by tag. |
| [schema-catalogue.md](schema-catalogue.md) | Every request and response schema. |
| [error-codes.md](error-codes.md) | Machine-readable `code` values and HTTP statuses. |
| [authentication.md](authentication.md) | Login, MFA, refresh, and logout for both clients. |
| [file-uploads.md](file-uploads.md) | Multipart uploads and raw-byte downloads. |
| [react-integration.md](react-integration.md) | Browser client rules. |
| [flutter-integration.md](flutter-integration.md) | Mobile client rules. |
| [missing-functionality.md](missing-functionality.md) | Gaps against the customer and staff workflows, with compatibility notes. |
| [examples/](examples) | Captured and illustrative payloads. |

Import `openapi.json` in Postman with **Import → OpenAPI 3**. Generate clients with OpenAPI Generator 7 or `openapi-typescript`. Treat JSON numbers for money as decimal values, as described in the integration guides.

# File upload integration

Uploads use `multipart/form-data`. The file part name is `file`. The document classification is a query parameter, not a form field. PDF, JPEG, and PNG are accepted only when the bytes match the declared content type. The limit is 10 MB (`app.customer.documents.max-bytes` and the servlet multipart limit). A rejected file returns `400 DOCUMENT_REJECTED` or `400 MALWARE_DETECTED`.

Do not set `Content-Type` yourself on the multipart request. The browser or HTTP client must add the boundary. Keep the bearer token on the request.

| Upload | Classification query | Success |
| --- | --- | --- |
| `POST /api/v1/customers/{id}/documents` | `documentType`: `IDENTITY`, `PROOF_OF_ADDRESS`, `PAYSLIP`, `BANK_STATEMENT`, `CAC`, `FINANCIAL_STATEMENT`, `BOARD_RESOLUTION`, `DIRECTOR_IDENTIFICATION`, `COLLATERAL` | 201, JSON envelope, `DocumentResponse` |
| `POST /api/v1/loan-applications/{id}/documents` | `documentCode`: one of the product document codes when the product defines them | 201, JSON envelope, metadata only |
| `POST /api/v1/collaterals/{id}/documents` | `documentCode`: `TITLE`, `LEGAL_OPINION`, `SEARCH_REPORT`, `INSURANCE_POLICY`, `VALUATION_REPORT`, `PHOTOGRAPH`, `OTHER` | 201, JSON envelope |
| `POST /api/v1/guarantors/{id}/documents` | `documentCode`: `GUARANTEE_AGREEMENT`, `IDENTITY`, `INCOME_EVIDENCE`, `CONSENT`, `OTHER` | 201, JSON envelope |

Customer, collateral, and guarantor files can be downloaded:

- `GET /api/v1/customers/{id}/documents/{documentId}`
- `GET /api/v1/collaterals/{id}/documents/{documentId}`
- `GET /api/v1/guarantors/{id}/documents/{documentId}`

The agreement PDF is `GET /api/v1/loan-offers/{id}/agreement/document`.

Those downloads return raw bytes with `Content-Disposition: attachment` and the stored content type. They are not wrapped in the JSON envelope. A failure on the same URL is JSON `ApiError`.

A loan-application supporting document can be listed at `GET /api/v1/loan-applications/{id}/documents` and cannot be downloaded. The list returns the file name, content type, size, and SHA-256 checksum. That gap is recorded in `missing-functionality.md`.

Report exports are not multipart. `POST /api/v1/reports/exports` returns the job id. When `status` is `PENDING` the HTTP status is 202. Poll `GET /api/v1/reports/exports/{id}`. Download with `GET /api/v1/reports/exports/{id}/download?token=...`. The token is the one returned to the requester. The body is CSV, Excel, or PDF bytes.

## Browser

```javascript
const body = new FormData();
body.append("file", file);
await fetch(`${baseUrl}/api/v1/customers/${customerId}/documents?documentType=IDENTITY`, {
  method: "POST",
  headers: { Authorization: `Bearer ${accessToken}` },
  body,
});
```

A cross-origin browser may send `Authorization` and `Content-Type`. It cannot send `X-Client-Channel` until that header is added to the CORS allow-list. Omitting the header stores `WEB`, which is the right value for the web app.

## Flutter

```dart
final request = http.MultipartRequest(
  "POST",
  Uri.parse("$baseUrl/api/v1/customers/$customerId/documents?documentType=IDENTITY"),
);
request.headers["Authorization"] = "Bearer $accessToken";
request.headers["X-Client-Channel"] = "MOBILE";
request.files.add(await http.MultipartFile.fromPath("file", path));
```

Camera rolls that produce HEIC or WebP are rejected. Convert to JPEG or PNG before upload. Use the same part name `file` from an in-memory `MultipartFile` when the bytes are not a path.

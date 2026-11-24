# File upload integration guide

Uploads use `multipart/form-data`. Browsers and Flutter both send that encoding. The file part name is `file`. The classifier is a required query parameter. The platform limit is 10 MB for the file and 12 MB for the request.

Allowed upload types are PDF, JPEG, and PNG. The service scans the bytes before it stores them. A rejected file returns `DOCUMENT_REJECTED`. A scanner hit returns `MALWARE_DETECTED`. Both are HTTP 400 and use `ApiError`.

| Upload | Query parameter | Values |
| --- | --- | --- |
| `POST /api/v1/customers/{id}/documents` | `documentType` | `IDENTITY`, `PROOF_OF_ADDRESS`, `PAYSLIP`, `BANK_STATEMENT`, `CAC`, `FINANCIAL_STATEMENT`, `BOARD_RESOLUTION`, `DIRECTOR_IDENTIFICATION`, `COLLATERAL` |
| `POST /api/v1/loan-applications/{id}/documents` | `documentCode` | A document code from the product. Replacing a code keeps the previous upload as history and returns the new metadata. |
| `POST /api/v1/collaterals/{id}/documents` | `documentCode` | `TITLE`, `VALUATION_REPORT`, `INSURANCE_POLICY`, `LEGAL_OPINION`, `SEARCH_REPORT`, `PHOTOGRAPH`, `OTHER` |
| `POST /api/v1/guarantors/{id}/documents` | `documentCode` | `GUARANTEE_AGREEMENT`, `IDENTITY`, `INCOME_EVIDENCE`, `CONSENT`, `OTHER` |

A successful upload is HTTP 201 and the success envelope. `data` contains the document id, file name, content type, size, and checksum. It does not contain the bytes.

## Browser

```javascript
const body = new FormData();
body.append("file", file, file.name);
const response = await fetch(
  `${baseUrl}/api/v1/customers/${customerId}/documents?documentType=IDENTITY`,
  {
    method: "POST",
    headers: {
      Authorization: `Bearer ${accessToken}`,
      "X-Correlation-Id": correlationId,
    },
    body,
  },
);
```

Do not set `Content-Type` yourself. The browser adds the multipart boundary.

## Flutter

```dart
final form = FormData.fromMap({
  "file": await MultipartFile.fromFile(path, filename: "identity.pdf"),
});
final response = await dio.post(
  "/api/v1/customers/$customerId/documents",
  data: form,
  queryParameters: {"documentType": "IDENTITY"},
  options: Options(headers: {"Authorization": "Bearer $accessToken"}),
);
```

## Downloads

These routes return raw bytes on success, with `Content-Disposition: attachment`. They return `ApiError` JSON on failure. Request `responseType` bytes in Dio, and use `response.blob()` in the browser. Do not base64-decode a successful body.

| Download | Notes |
| --- | --- |
| `GET /api/v1/customers/{id}/documents/{documentId}` | Customer identity and KYC files. |
| `GET /api/v1/collaterals/{id}/documents/{documentId}` | Collateral evidence. |
| `GET /api/v1/guarantors/{id}/documents/{documentId}` | Guarantor evidence. |
| `GET /api/v1/loan-offers/{id}/agreement/document` | Agreement PDF. Optional `version` and `kind` query parameters. |
| `GET /api/v1/reports/exports/{id}/download?token=` | Staff export. The token comes from the export status payload and is required. |

Loan application documents can be listed at `GET /api/v1/loan-applications/{id}/documents`. That response is metadata only. There is no route that returns those bytes.

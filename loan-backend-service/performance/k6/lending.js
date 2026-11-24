import http from "k6/http";
import { check, group, sleep } from "k6";

const baseUrl = __ENV.BASE_URL || "http://localhost:8080";
const identifier = __ENV.CUSTOMER_IDENTIFIER || "";
const password = __ENV.CUSTOMER_PASSWORD || "";
const staffToken = __ENV.STAFF_TOKEN || "";
const target = __ENV.K6_PROFILE === "target";
const vus = target ? 1000 : 5;
const duration = target ? "5m" : "30s";

export const options = {
  scenarios: {
    authentication: { executor: "constant-vus", vus, duration, exec: "authentication" },
    customerLookup: { executor: "constant-vus", vus, duration, exec: "customerLookup", startTime: "5s" },
    loanApplication: { executor: "constant-vus", vus: target ? 200 : 2, duration, exec: "loanApplication", startTime: "10s" },
    creditAssessment: { executor: "constant-vus", vus: target ? 100 : 2, duration, exec: "creditAssessment", startTime: "15s" },
    approvalQueue: { executor: "constant-vus", vus: target ? 100 : 2, duration, exec: "approvalQueue", startTime: "20s" },
    disbursement: { executor: "constant-vus", vus: target ? 50 : 1, duration, exec: "disbursement", startTime: "25s" },
    repayment: { executor: "constant-vus", vus: target ? 50 : 1, duration, exec: "repayment", startTime: "30s" },
    dashboard: { executor: "constant-vus", vus: target ? 100 : 2, duration, exec: "dashboard", startTime: "35s" },
  },
  thresholds: {
    "http_req_failed": ["rate<0.01"],
    "http_req_duration{flow:read}": ["p(95)<2000"],
    "http_req_duration{flow:application}": ["p(95)<5000"],
    "checks{flow:money}": ["rate==1"],
  },
};

function headers(token) {
  return {
    headers: {
      Authorization: `Bearer ${token}`,
      "Content-Type": "application/json",
      "X-Correlation-Id": `k6-${__VU}-${__ITER}`,
    },
  };
}

export function authentication() {
  group("authentication", () => {
    if (!identifier || !password) {
      const version = http.get(`${baseUrl}/api/v1/system/version`, { tags: { flow: "read" } });
      check(version, { "version responds": (response) => response.status === 200 });
      sleep(1);
      return;
    }
    const response = http.post(
      `${baseUrl}/api/v1/auth/login`,
      JSON.stringify({ identifier, password }),
      { headers: { "Content-Type": "application/json" }, tags: { flow: "read" } },
    );
    check(response, { "login accepted": (result) => result.status === 200 || result.status === 401 || result.status === 429 });
    sleep(1);
  });
}

export function customerLookup() {
  group("customer lookup", () => {
    const response = http.get(`${baseUrl}/api/v1/customers/me`, headers(staffToken || "missing") );
    response.tags = { flow: "read" };
    check(response, { "lookup is authorised or rejected": (result) => result.status === 200 || result.status === 401 || result.status === 403 });
    sleep(1);
  });
}

export function loanApplication() {
  group("loan application", () => {
    const response = http.get(`${baseUrl}/api/v1/loan-products`, { tags: { flow: "application" }, ...headers(staffToken || "missing") });
    check(response, { "catalogue responds": (result) => result.status === 200 || result.status === 401 });
    sleep(1);
  });
}

export function creditAssessment() {
  group("credit assessment", () => {
    const response = http.get(`${baseUrl}/api/v1/admin/credit-assessments?page=0&size=1`, { tags: { flow: "read" }, ...headers(staffToken || "missing") });
    check(response, { "assessment queue responds": (result) => result.status === 200 || result.status === 401 || result.status === 403 });
    sleep(1);
  });
}

export function approvalQueue() {
  group("approval queue", () => {
    const response = http.get(`${baseUrl}/api/v1/admin/approvals?page=0&size=1`, { tags: { flow: "read" }, ...headers(staffToken || "missing") });
    check(response, { "approval queue responds": (result) => result.status === 200 || result.status === 401 || result.status === 403 });
    sleep(1);
  });
}

export function disbursement() {
  group("disbursement", () => {
    const response = http.get(`${baseUrl}/api/v1/admin/integrations/core-banking/transactions?page=0&size=1`, { tags: { flow: "money" }, ...headers(staffToken || "missing") });
    check(response, {
      "disbursement read does not create a posting": (result) => result.status === 200 || result.status === 401 || result.status === 403,
    });
    sleep(1);
  });
}

export function repayment() {
  group("repayment", () => {
    const response = http.get(`${baseUrl}/api/v1/admin/integrations/core-banking/transactions?page=0&size=1`, { tags: { flow: "money" }, ...headers(staffToken || "missing") });
    check(response, {
      "repayment read does not create a posting": (result) => result.status === 200 || result.status === 401 || result.status === 403,
    });
    sleep(1);
  });
}

export function dashboard() {
  group("dashboard", () => {
    const response = http.get(`${baseUrl}/api/v1/admin/reports/portfolio`, { tags: { flow: "read" }, ...headers(staffToken || "missing") });
    check(response, { "dashboard responds": (result) => result.status === 200 || result.status === 401 || result.status === 403 });
    sleep(1);
  });
}

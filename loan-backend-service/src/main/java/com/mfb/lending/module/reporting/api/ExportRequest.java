package com.mfb.lending.module.reporting.api;

import java.time.LocalDate;
import java.util.UUID;

import com.mfb.lending.module.reporting.domain.ExportFormat;
import com.mfb.lending.module.reporting.domain.ReportCode;
import jakarta.validation.constraints.NotNull;

public class ExportRequest {

    @NotNull
    private ReportCode report;

    @NotNull
    private ExportFormat format;

    private LocalDate startDate;
    private LocalDate endDate;
    private UUID branchId;
    private UUID productId;
    private String customerSegment;
    private String loanStatus;
    private String riskCategory;
    private UUID loanOfficerId;
    private String security;
    private String repaymentStatus;

    public ReportQuery toQuery() {
        ReportQuery query = new ReportQuery();
        query.setStartDate(startDate);
        query.setEndDate(endDate);
        query.setBranchId(branchId);
        query.setProductId(productId);
        query.setCustomerSegment(customerSegment);
        query.setLoanStatus(loanStatus);
        query.setRiskCategory(riskCategory);
        query.setLoanOfficerId(loanOfficerId);
        query.setSecurity(security);
        query.setRepaymentStatus(repaymentStatus);
        return query;
    }

    public ReportCode getReport() {
        return report;
    }

    public void setReport(ReportCode report) {
        this.report = report;
    }

    public ExportFormat getFormat() {
        return format;
    }

    public void setFormat(ExportFormat format) {
        this.format = format;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public void setStartDate(LocalDate startDate) {
        this.startDate = startDate;
    }

    public LocalDate getEndDate() {
        return endDate;
    }

    public void setEndDate(LocalDate endDate) {
        this.endDate = endDate;
    }

    public UUID getBranchId() {
        return branchId;
    }

    public void setBranchId(UUID branchId) {
        this.branchId = branchId;
    }

    public UUID getProductId() {
        return productId;
    }

    public void setProductId(UUID productId) {
        this.productId = productId;
    }

    public String getCustomerSegment() {
        return customerSegment;
    }

    public void setCustomerSegment(String customerSegment) {
        this.customerSegment = customerSegment;
    }

    public String getLoanStatus() {
        return loanStatus;
    }

    public void setLoanStatus(String loanStatus) {
        this.loanStatus = loanStatus;
    }

    public String getRiskCategory() {
        return riskCategory;
    }

    public void setRiskCategory(String riskCategory) {
        this.riskCategory = riskCategory;
    }

    public UUID getLoanOfficerId() {
        return loanOfficerId;
    }

    public void setLoanOfficerId(UUID loanOfficerId) {
        this.loanOfficerId = loanOfficerId;
    }

    public String getSecurity() {
        return security;
    }

    public void setSecurity(String security) {
        this.security = security;
    }

    public String getRepaymentStatus() {
        return repaymentStatus;
    }

    public void setRepaymentStatus(String repaymentStatus) {
        this.repaymentStatus = repaymentStatus;
    }
}

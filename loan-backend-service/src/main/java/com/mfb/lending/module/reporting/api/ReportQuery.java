package com.mfb.lending.module.reporting.api;

import java.time.LocalDate;
import java.util.UUID;

public class ReportQuery {

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
    private Integer page;
    private Integer size;

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

    public Integer getPage() {
        return page;
    }

    public void setPage(Integer page) {
        this.page = page;
    }

    public Integer getSize() {
        return size;
    }

    public void setSize(Integer size) {
        this.size = size;
    }
}

package com.lumileaf.lumi.model;

import jakarta.persistence.*;
import java.time.LocalDate;

@Entity
public class StockProduction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String lotNumber;
    private LocalDate createdDate;
    private String status = "NOT TESTED";
    private String pdfReportPath;
    private String rejectNote;

    // Tea Grades
    private Double op1 = 0.0;
    private Double opa = 0.0;
    private Double bop1 = 0.0;
    private Double pekoe = 0.0;
    private Double bop = 0.0;
    private Double bopf = 0.0;
    private Double eb = 0.0;
    private Double ffsp = 0.0;
    private Double ffexs = 0.0;
    private Double bop1A = 0.0;
    private Double dust = 0.0;
    private Double bm = 0.0;
    private Double bp = 0.0;
    private Double refusedTea = 0.0;
    private Double total = 0.0;

    // Stock Quantities
    private Double testedQty = 0.0;
    private Double notTestedQty = 0.0;

    // ---------------- Core Getters & Setters ----------------

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getLotNumber() {
        return lotNumber;
    }

    public void setLotNumber(String lotNumber) {
        this.lotNumber = lotNumber;
    }

    public LocalDate getCreatedDate() {
        return createdDate;
    }

    public void setCreatedDate(LocalDate createdDate) {
        this.createdDate = createdDate;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getPdfReportPath() {
        return pdfReportPath;
    }

    public void setPdfReportPath(String pdfReportPath) {
        this.pdfReportPath = pdfReportPath;
    }

    public String getRejectNote() {
        return rejectNote;
    }

    public void setRejectNote(String rejectNote) {
        this.rejectNote = rejectNote;
    }

    public Double getTotal() {
        return total;
    }

    public void setTotal(Double total) {
        this.total = total;
    }

    public Double getTestedQty() {
        return testedQty;
    }

    public void setTestedQty(Double testedQty) {
        this.testedQty = testedQty;
    }

    public Double getNotTestedQty() {
        return notTestedQty;
    }

    public void setNotTestedQty(Double notTestedQty) {
        this.notTestedQty = notTestedQty;
    }

    // ---------------- Tea Grade Getters & Setters ----------------

    public Double getOp1() { return op1; }
    public void setOp1(Double op1) { this.op1 = op1; }

    public Double getOpa() { return opa; }
    public void setOpa(Double opa) { this.opa = opa; }

    public Double getBop1() { return bop1; }
    public void setBop1(Double bop1) { this.bop1 = bop1; }

    public Double getPekoe() { return pekoe; }
    public void setPekoe(Double pekoe) { this.pekoe = pekoe; }

    public Double getBop() { return bop; }
    public void setBop(Double bop) { this.bop = bop; }

    public Double getBopf() { return bopf; }
    public void setBopf(Double bopf) { this.bopf = bopf; }

    public Double getEb() { return eb; }
    public void setEb(Double eb) { this.eb = eb; }

    public Double getFfsp() { return ffsp; }
    public void setFfsp(Double ffsp) { this.ffsp = ffsp; }

    public Double getFfexs() { return ffexs; }
    public void setFfexs(Double ffexs) { this.ffexs = ffexs; }

    public Double getBop1A() { return bop1A; }
    public void setBop1A(Double bop1A) { this.bop1A = bop1A; }

    public Double getDust() { return dust; }
    public void setDust(Double dust) { this.dust = dust; }

    public Double getBm() { return bm; }
    public void setBm(Double bm) { this.bm = bm; }

    public Double getBp() { return bp; }
    public void setBp(Double bp) { this.bp = bp; }

    public Double getRefusedTea() { return refusedTea; }
    public void setRefusedTea(Double refusedTea) { this.refusedTea = refusedTea; }

}
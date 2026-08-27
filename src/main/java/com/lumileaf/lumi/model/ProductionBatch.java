package com.lumileaf.lumi.model;

import jakarta.persistence.*;
import java.time.LocalDate;


@Entity
@Table(name = "production_batches")
public class ProductionBatch {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // ── FIX (ProductionBatch #4): Repurposed this field to store the original
    // weighing/gate batch ID (e.g. "113 ( ESTATE )" or a "+"-joined combined string
    // like "LOTFA2606-2 + AMG2606B-2"), set by RollingController.saveRollingRecord().
    // This is a DIFFERENT ID space from lotNumber (the production lot number) —
    // ProductionController uses this field to correctly look up WaitingPoint
    // records for the green-leaf arrival date and weight.
    @Column(name = "production_id")
    private String productionId;

    // Add this field to your ProductionBatch class
    @Column(name = "transaction_date")
    private LocalDate transactionDate;

    public LocalDate getTransactionDate() { return transactionDate; }
    public void setTransactionDate(LocalDate transactionDate) { this.transactionDate = transactionDate; }

    @Column(name = "production_date")
    private LocalDate productionDate;

    // ✅ NEW: Track when drying actually occurs (separate from green leaf arrival date)
    @Column(name = "drying_date")
    private LocalDate dryingDate;

    @Column(name = "lot_number")
    private String lotNumber;

    @Column(name = "rolling_date")
    private LocalDate rollingDate;

    @Column(name = "green_leaves_qty")
    private Double greenLeavesQty = 0.0;

    @Column(name = "estimated_made_tea")
    private Double estimatedMadeTea = 0.0;

    @Column(name = "actual_made_tea")
    private Double actualMadeTea = 0.0;

    // --- ROLLING DATA (WITH COLUMN MAPPINGS) ---
    @Column(name = "dhool1")
    private Double dhool1 = 0.0;

    @Column(name = "dhool2")
    private Double dhool2 = 0.0;

    @Column(name = "dhool3")
    private Double dhool3 = 0.0;

    @Column(name = "big_bulk")
    private Double bigBulk = 0.0;

    // --- DRYING DATA ---
    @Column(name = "temperature")
    private Double temperature = 0.0;

    @Column(name = "moisture_content")
    private Double moistureContent = 0.0;

    @Column(name = "drying_officer")
    private String dryingOfficer;
    @Column(name = "moisture_d1")
    private Double moistureD1 = 0.0;

    @Column(name = "moisture_d2")
    private Double moistureD2 = 0.0;

    @Column(name = "moisture_d3")
    private Double moistureD3 = 0.0;

    @Column(name = "moisture_bb")
    private Double moistureBB = 0.0;

    // --- DRY OUTPUT WEIGHT FIELDS ---
    @Column(name = "dry_dhool1")
    private Double dryDhool1 = 0.0;

    @Column(name = "dry_dhool2")
    private Double dryDhool2 = 0.0;

    @Column(name = "dry_dhool3")
    private Double dryDhool3 = 0.0;

    @Column(name = "dry_big_bulk")
    private Double dryBigBulk = 0.0;

    @Column(name = "drying_loss")
    private Double dryingLoss = 0.0;

    // --- QA DATA ---
    @Column(name = "humidity")
    private Double humidity = 0.0;

    @Column(name = "status")
    private String status = "PENDING";

    @Column(name = "processed_byqa")
    private boolean processedByQA = false;

    // --- GRADE DATA ---
    @Column(name = "op1")
    private Double op1 = 0.0;

    @Column(name = "opa")
    private Double opa = 0.0;

    @Column(name = "bop1")
    private Double bop1 = 0.0;

    @Column(name = "pekoe")
    private Double pekoe = 0.0;

    @Column(name = "bop")
    private Double bop = 0.0;

    @Column(name = "bopf")
    private Double bopf = 0.0;

    @Column(name = "eb")
    private Double eb = 0.0;

    @Column(name = "ffsp")
    private Double ffsp = 0.0;

    @Column(name = "ffexs")
    private Double ffexs = 0.0;

    @Column(name = "dust")
    private Double dust = 0.0;

    @Column(name = "bm")
    private Double bm = 0.0;

    @Column(name = "bp")
    private Double bp = 0.0;

    @Column(name = "refused_tea")
    private Double refusedTea = 0.0;

    @Column(name = "reject_note")
    private String rejectNote;

    public ProductionBatch() {}

    // --- CORE GETTERS/SETTERS ---
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getProductionId() { return productionId; }
    public void setProductionId(String productionId) { this.productionId = productionId; }

    public LocalDate getProductionDate() { return productionDate; }
    public void setProductionDate(LocalDate productionDate) { this.productionDate = productionDate; }

    // ✅ NEW: Drying date getters/setters
    public LocalDate getDryingDate() { return dryingDate; }
    public void setDryingDate(LocalDate dryingDate) { this.dryingDate = dryingDate; }

    // NEW: Convenience accessor required by templates: greenLeafArrivedDate
    // Returns productionDate when available, otherwise falls back to transactionDate.
    public LocalDate getGreenLeafArrivedDate() {
        if (this.productionDate != null) return this.productionDate;
        return this.transactionDate;
    }
    // Optional setter so forms binding to greenLeafArrivedDate can write back.
    public void setGreenLeafArrivedDate(LocalDate date) {
        this.productionDate = date;
    }

    public String getLotNumber() { return lotNumber; }
    public void setLotNumber(String lotNumber) { this.lotNumber = lotNumber; }

    public LocalDate getRollingDate() { return rollingDate; }
    public void setRollingDate(LocalDate rollingDate) { this.rollingDate = rollingDate; }

    public Double getTemperature() { return temperature; }
    public void setTemperature(Double temperature) { this.temperature = temperature; }

    public Double getMoistureContent() { return moistureContent; }
    public void setMoistureContent(Double moistureContent) { this.moistureContent = moistureContent; }

    public Double getHumidity() { return humidity; }
    public void setHumidity(Double humidity) { this.humidity = humidity; }

    public String getDryingOfficer() { return dryingOfficer; }
    public void setDryingOfficer(String dryingOfficer) { this.dryingOfficer = dryingOfficer; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    // --- ROLLING DATA GETTERS/SETTERS ---
    public Double getDhool1() { return dhool1; }
    public void setDhool1(Double dhool1) { this.dhool1 = dhool1; }

    public Double getDhool2() { return dhool2; }
    public void setDhool2(Double dhool2) { this.dhool2 = dhool2; }

    public Double getDhool3() { return dhool3; }
    public void setDhool3(Double dhool3) { this.dhool3 = dhool3; }

    public Double getBigBulk() { return bigBulk; }
    public void setBigBulk(Double bigBulk) { this.bigBulk = bigBulk; }

    public Double getGreenLeavesQty() { return greenLeavesQty; }
    public void setGreenLeavesQty(Double greenLeavesQty) { this.greenLeavesQty = greenLeavesQty; }

    public Double getEstimatedMadeTea() { return estimatedMadeTea; }
    public void setEstimatedMadeTea(Double estimatedMadeTea) { this.estimatedMadeTea = estimatedMadeTea; }

    public Double getActualMadeTea() { return actualMadeTea; }
    public void setActualMadeTea(Double actualMadeTea) { this.actualMadeTea = actualMadeTea; }

    // --- DRY OUTPUT WEIGHT GETTERS/SETTERS ---
    public Double getDryDhool1() { return dryDhool1; }
    public void setDryDhool1(Double dryDhool1) { this.dryDhool1 = dryDhool1; }

    public Double getDryDhool2() { return dryDhool2; }
    public void setDryDhool2(Double dryDhool2) { this.dryDhool2 = dryDhool2; }

    public Double getDryDhool3() { return dryDhool3; }
    public void setDryDhool3(Double dryDhool3) { this.dryDhool3 = dryDhool3; }

    public Double getDryBigBulk() { return dryBigBulk; }
    public void setDryBigBulk(Double dryBigBulk) { this.dryBigBulk = dryBigBulk; }

    public Double getDryingLoss() { return dryingLoss; }
    public void setDryingLoss(Double dryingLoss) { this.dryingLoss = dryingLoss; }

    // --- GRADE GETTERS/SETTERS ---
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

    public Double getDust() { return dust; }
    public void setDust(Double dust) { this.dust = dust; }

    public Double getBm() { return bm; }
    public void setBm(Double bm) { this.bm = bm; }

    public Double getBp() { return bp; }
    public void setBp(Double bp) { this.bp = bp; }

    public Double getRefusedTea() { return refusedTea; }
    public void setRefusedTea(Double refusedTea) { this.refusedTea = refusedTea; }

    public boolean isProcessedByQA() { return processedByQA; }
    public void setProcessedByQA(boolean processedByQA) { this.processedByQA = processedByQA; }

    public String getRejectNote() { return rejectNote; }
    public void setRejectNote(String rejectNote) { this.rejectNote = rejectNote; }
    public Double getMoistureD1() { return moistureD1; }
    public void setMoistureD1(Double moistureD1) { this.moistureD1 = moistureD1; }

    public Double getMoistureD2() { return moistureD2; }
    public void setMoistureD2(Double moistureD2) { this.moistureD2 = moistureD2; }

    public Double getMoistureD3() { return moistureD3; }
    public void setMoistureD3(Double moistureD3) { this.moistureD3 = moistureD3; }

    public Double getMoistureBB() { return moistureBB; }
    public void setMoistureBB(Double moistureBB) { this.moistureBB = moistureBB; }
}

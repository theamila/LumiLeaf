package com.lumileaf.lumi.model;

import jakarta.persistence.*;
import java.time.LocalDate;

@Entity
public class BlendBalance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String blendId; // The ID for this specific remnant set

    // --- DATE TRACKING FOR AUDITS ---
    private LocalDate entryDate;

    // Automatically stamps the current date when a new record is saved
    @PrePersist
    protected void onCreate() {
        if (this.entryDate == null) {
            this.entryDate = LocalDate.now();
        }
    }

    // --- TEA GRADE FIELDS ---
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

    // --- STANDARD GETTERS AND SETTERS ---

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getBlendId() { return blendId; }
    public void setBlendId(String blendId) { this.blendId = blendId; }

    public LocalDate getEntryDate() { return entryDate; }
    public void setEntryDate(LocalDate entryDate) { this.entryDate = entryDate; }

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
package com.lumileaf.lumi.model;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalTime;

@Entity
public class GradeTransaction {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private LocalDate date;
    private LocalTime time;
    private String officer;

    private String sourceGrade;
    private Double sourceQty;
    private String sourceLotNumber;   // NEW

    // Resulting Target Grades
    private Double op1=0.0, opa=0.0, bop1=0.0, pekoe=0.0, bop=0.0, bopf=0.0, eb=0.0, ffsp=0.0, ffexs=0.0, dust=0.0, bm=0.0, bp=0.0, refusedTea=0.0;

    // --- GETTERS & SETTERS ---
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public LocalDate getDate() { return date; }
    public void setDate(LocalDate date) { this.date = date; }
    public LocalTime getTime() { return time; }
    public void setTime(LocalTime time) { this.time = time; }
    public String getOfficer() { return officer; }
    public void setOfficer(String officer) { this.officer = officer; }
    public String getSourceGrade() { return sourceGrade; }
    public void setSourceGrade(String sourceGrade) { this.sourceGrade = sourceGrade; }
    public Double getSourceQty() { return sourceQty; }
    public void setSourceQty(Double sourceQty) { this.sourceQty = sourceQty; }
    public String getSourceLotNumber() { return sourceLotNumber; }
    public void setSourceLotNumber(String sourceLotNumber) { this.sourceLotNumber = sourceLotNumber; }

    public Double getOp1() { return op1; } public void setOp1(Double op1) { this.op1 = op1; }
    public Double getOpa() { return opa; } public void setOpa(Double opa) { this.opa = opa; }
    public Double getBop1() { return bop1; } public void setBop1(Double bop1) { this.bop1 = bop1; }
    public Double getPekoe() { return pekoe; } public void setPekoe(Double pekoe) { this.pekoe = pekoe; }
    public Double getBop() { return bop; } public void setBop(Double bop) { this.bop = bop; }
    public Double getBopf() { return bopf; } public void setBopf(Double bopf) { this.bopf = bopf; }
    public Double getEb() { return eb; } public void setEb(Double eb) { this.eb = eb; }
    public Double getFfsp() { return ffsp; } public void setFfsp(Double ffsp) { this.ffsp = ffsp; }
    public Double getFfexs() { return ffexs; } public void setFfexs(Double ffexs) { this.ffexs = ffexs; }
    public Double getDust() { return dust; } public void setDust(Double dust) { this.dust = dust; }
    public Double getBm() { return bm; } public void setBm(Double bm) { this.bm = bm; }
    public Double getBp() { return bp; } public void setBp(Double bp) { this.bp = bp; }
    public Double getRefusedTea() { return refusedTea; } public void setRefusedTea(Double refusedTea) { this.refusedTea = refusedTea; }
}
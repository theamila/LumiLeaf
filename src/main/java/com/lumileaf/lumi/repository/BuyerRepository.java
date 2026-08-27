package com.lumileaf.lumi.repository;

import com.lumileaf.lumi.model.Buyer;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BuyerRepository extends JpaRepository<Buyer, Long> {
}
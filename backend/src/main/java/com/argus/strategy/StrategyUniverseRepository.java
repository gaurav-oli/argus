package com.argus.strategy;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StrategyUniverseRepository extends JpaRepository<StrategyUniverse, String> {

	List<StrategyUniverse> findByActiveTrue();

	long countByActiveTrue();
}

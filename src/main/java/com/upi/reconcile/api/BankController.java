package com.upi.reconcile.api;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Bank scorecard endpoint — ARCHITECTURE.md §7.
 * <p>
 * GET /api/banks/scorecard
 *   → per-bank real + live-simulated BD/TD rate, avg resolution time, ranked
 * <p>
 * TODO: Implement aggregation queries for bank performance metrics.
 */
@Slf4j
@RestController
@RequestMapping("/api/banks")
public class BankController {

    @GetMapping("/scorecard")
    public ResponseEntity<List<Map<String, Object>>> getBankScorecard() {
        log.info("Fetching bank scorecard");
        // TODO: Aggregate per-bank BD/TD rates, avg resolution time, ranking
        return ResponseEntity.ok(Collections.emptyList());
    }
}

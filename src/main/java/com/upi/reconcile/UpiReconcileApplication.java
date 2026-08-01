package com.upi.reconcile;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class UpiReconcileApplication {

    public static void main(String[] args) {
        SpringApplication.run(UpiReconcileApplication.class, args);
    }
}

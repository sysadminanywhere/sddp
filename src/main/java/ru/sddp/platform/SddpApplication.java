package ru.sddp.platform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class SddpApplication {
    public static void main(String[] args) {
        SpringApplication.run(SddpApplication.class, args);
    }
}

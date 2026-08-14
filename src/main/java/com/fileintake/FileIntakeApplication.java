package com.fileintake;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class FileIntakeApplication {

    public static void main(String[] args) {
        SpringApplication.run(FileIntakeApplication.class, args);
    }
}

package com.fileintake.reaper;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Entry point for the k8s CronJob: run the reaper once and exit, rather than staying up as a
 * long-lived process. Activated via {@code --spring.profiles.active=reaper}; see
 * application-reaper.yml for the accompanying web-application-type=none so no port is opened.
 */
@Component
@Profile("reaper")
public class ReaperRunner implements CommandLineRunner {

    private final ReaperJob reaperJob;
    private final ApplicationContext context;

    public ReaperRunner(ReaperJob reaperJob, ApplicationContext context) {
        this.reaperJob = reaperJob;
        this.context = context;
    }

    @Override
    public void run(String... args) {
        reaperJob.reap();
        System.exit(SpringApplication.exit(context, () -> 0));
    }
}

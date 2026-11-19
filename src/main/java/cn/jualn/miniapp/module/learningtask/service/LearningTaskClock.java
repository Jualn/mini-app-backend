package cn.jualn.miniapp.module.learningtask.service;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration("learningTaskClockConfiguration")
public class LearningTaskClock {
    @Bean("learningTaskClock")
    public Clock learningTaskClock() { return Clock.systemUTC(); }
}

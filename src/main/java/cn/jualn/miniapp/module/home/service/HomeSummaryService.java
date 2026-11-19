package cn.jualn.miniapp.module.home.service;

import cn.jualn.miniapp.common.async.ContextCopyDecorator;
import cn.jualn.miniapp.common.web.AdminQuery;
import cn.jualn.miniapp.module.home.model.HomeContent.Module;
import cn.jualn.miniapp.module.home.model.HomeContent.Status;
import cn.jualn.miniapp.module.home.model.HomeContent.Summary;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.concurrent.CustomizableThreadFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.Supplier;

@Service
@Slf4j
public class HomeSummaryService {
    private static final long REQUEST_BUDGET = Duration.ofSeconds(2).toNanos();
    private static final long MODULE_BUDGET = Duration.ofMillis(1500).toNanos();
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    private final HomeSummaryQueries queries;
    private final ContextCopyDecorator context = new ContextCopyDecorator();
    private final ExecutorService banners = executor("home-banners");
    private final ExecutorService articles = executor("home-articles");
    private final ExecutorService activities = executor("home-activities");

    public HomeSummaryService(HomeSummaryQueries queries) {
        this.queries = queries;
    }

    public Summary get(long requestStarted) {
        var at = OffsetDateTime.now(BUSINESS_ZONE);
        long deadline = Math.min(deadline(requestStarted), System.nanoTime() + MODULE_BUDGET);
        if (System.nanoTime() >= deadline) throw AdminQuery.unavailable();

        var bannerTask = submit(banners, "banners", queries::banners);
        var articleTask = submit(articles, "latestArticles", () -> queries.latestArticles(at));
        var activityTask = submit(activities, "upcomingActivities", () -> queries.upcomingActivities(at));
        var bannerResult = await(bannerTask, deadline);
        var articleResult = await(articleTask, deadline);
        var activityResult = await(activityTask, deadline);
        if (bannerResult.status() == Status.UNAVAILABLE && articleResult.status() == Status.UNAVAILABLE
                && activityResult.status() == Status.UNAVAILABLE) throw AdminQuery.unavailable();
        return new Summary(at, bannerResult, articleResult, activityResult);
    }

    private record Completed<T>(Module<T> module, long completedAt) {
    }

    private record Query<T>(String name, FutureTask<Completed<T>> task) {
    }

    private <T> Query<T> submit(ExecutorService executor, String name, Supplier<List<T>> query) {
        FutureTask<Completed<T>> task = new FutureTask<>(() -> {
            try {
                return new Completed<>(Module.of(query.get()), System.nanoTime());
            } catch (RuntimeException failure) {
                log.warn("result=failure module={} exceptionType={}", name, failure.getClass().getSimpleName());
                return new Completed<>(Module.unavailable(), System.nanoTime());
            }
        });
        try {
            executor.execute(context.decorate(task));
        } catch (RejectedExecutionException e) {
            task.cancel(false);
            log.warn("result=failure module={} exceptionType={}", name, e.getClass().getSimpleName());
        }
        return new Query<>(name, task);
    }

    private <T> Module<T> await(Query<T> query, long deadline) {
        try {
            var completed = query.task().get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            return completed.completedAt() <= deadline ? completed.module() : Module.unavailable();
        } catch (TimeoutException e) {
            log.warn("result=failure module={} exceptionType={}", query.name(), e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | CancellationException e) {
            log.debug("result=failure module={} exceptionType={}", query.name(), e.getClass().getSimpleName());
        } finally {
            query.task().cancel(true);
        }
        return Module.unavailable();
    }

    static long deadline(long started) {
        return started + REQUEST_BUDGET;
    }

    static ExecutorService executor(String name) {
        return new ThreadPoolExecutor(0, 2, 30, TimeUnit.SECONDS, new SynchronousQueue<>(),
                new CustomizableThreadFactory(name + "-"));
    }

    @PreDestroy
    void shutdown() {
        banners.shutdownNow();
        articles.shutdownNow();
        activities.shutdownNow();
    }
}

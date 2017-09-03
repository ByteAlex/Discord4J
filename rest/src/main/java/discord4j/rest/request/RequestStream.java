package discord4j.rest.request;

import discord4j.rest.http.client.ExchangeFilter;
import discord4j.rest.http.client.SimpleHttpClient;
import io.netty.handler.codec.http.HttpHeaders;
import org.reactivestreams.Publisher;
import reactor.core.publisher.EmitterProcessor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.ipc.netty.http.client.HttpClientException;
import reactor.retry.BackoffDelay;
import reactor.retry.Retry;
import reactor.retry.RetryContext;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Predicate;

public class RequestStream<T> {

	private final Retry<AtomicLong> RETRY = Retry.onlyIf(new Predicate<RetryContext<AtomicLong>>() {
		@Override
		public boolean test(RetryContext<AtomicLong> ctx) {
			Throwable exception = ctx.exception();
			if (exception instanceof HttpClientException) {
				HttpClientException httpException = (HttpClientException) exception;
				if (httpException.status().code() == 429) {
					boolean global = Boolean.valueOf(httpException.headers().get("X-RateLimit-Global"));
					long retryAfter = Long.valueOf(httpException.headers().get("Retry-After"));

					if (global) {
						globalRateLimiter.rateLimitFor(Duration.ofMillis(retryAfter));
					} else {
						ctx.applicationContext().set(retryAfter);
					}
				}
			}
			return false;
		}
	}).backoff(context -> new BackoffDelay(Duration.ofMillis(((AtomicLong) context.applicationContext()).get())));

	private final EmitterProcessor<DiscordRequest<T>> backing = EmitterProcessor.create(false);
	private final SimpleHttpClient httpClient;
	private final GlobalRateLimiter globalRateLimiter;

	RequestStream(Router router) {
		this.httpClient = router.httpClient;
		this.globalRateLimiter = router.globalRateLimiter;
	}

	void push(DiscordRequest<T> request) {
		backing.onNext(request);
	}

	void start() {
		read().subscribe(new Reader());
	}

	private Mono<DiscordRequest<T>> read() {
		return backing.next();
	}

	private class Reader implements Consumer<DiscordRequest<T>> {

		private volatile Duration sleepTime = Duration.ZERO;
		private final ExchangeFilter exchangeFilter = ExchangeFilter.builder()
				.responseFilter(response -> {
					HttpHeaders headers = response.responseHeaders();

					int remaining = headers.getInt("X-RateLimit-Remaining");
					if (remaining == 0) {
						long resetAt = Long.parseLong(headers.get("X-RateLimit-Reset"));
						long discordTime = OffsetDateTime.parse(headers.get("Date"),
								DateTimeFormatter.RFC_1123_DATE_TIME).toEpochSecond();

						sleepTime = Duration.ofSeconds(resetAt - discordTime);
					}
				})
				.build();

		@SuppressWarnings("ConstantConditions")
		@Override
		public void accept(DiscordRequest<T> req) {
			Mono.when(globalRateLimiter).compose(mono -> Mono.just(new Object()))
					.flatMap(e -> httpClient.exchange(req.getMethod(), req.getUri(), req.getBody(), req.getResponseType(), exchangeFilter))
					.retryWhen(RETRY)
					.materialize()
					.subscribe(signal -> {
						if (signal.isOnSubscribe()) {
							req.mono.onSubscribe(signal.getSubscription());
						} else if (signal.isOnNext()) {
							req.mono.onNext(signal.get());
						} else if (signal.isOnError()) {
							req.mono.onError(signal.getThrowable());
						} else if (signal.isOnComplete()) {
							req.mono.onComplete();
						}

						Mono.delay(sleepTime).subscribe(l -> {
							read().subscribe(this);
							sleepTime = Duration.ZERO;
						});
					});
		}
	}
}

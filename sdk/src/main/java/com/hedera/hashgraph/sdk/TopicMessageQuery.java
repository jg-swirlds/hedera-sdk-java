package com.hedera.hashgraph.sdk;

import com.google.errorprone.annotations.Var;
import com.hedera.hashgraph.sdk.proto.Timestamp;
import com.hedera.hashgraph.sdk.proto.TransactionID;
import com.hedera.hashgraph.sdk.proto.mirror.ConsensusServiceGrpc;
import com.hedera.hashgraph.sdk.proto.mirror.ConsensusTopicQuery;
import com.hedera.hashgraph.sdk.proto.mirror.ConsensusTopicResponse;
import io.grpc.CallOptions;
import io.grpc.ClientCall;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.ClientCalls;
import io.grpc.stub.StreamObserver;
import java8.util.function.BiConsumer;
import java8.util.function.Consumer;
import java8.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.threeten.bp.Duration;
import org.threeten.bp.Instant;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public final class TopicMessageQuery {

    private static final Logger LOGGER = LoggerFactory.getLogger(TopicMessageQuery.class);

    private final ConsensusTopicQuery.Builder builder;
    private Runnable completionHandler = this::onComplete;
    private BiConsumer<Throwable, TopicMessage> errorHandler = this::onError;
    private int maxAttempts = 10;
    private Duration maxBackoff = Duration.ofSeconds(8L);
    private Predicate<Throwable> retryHandler = this::shouldRetry;

    public TopicMessageQuery() {
        builder = ConsensusTopicQuery.newBuilder();
    }

    public TopicMessageQuery setTopicId(TopicId topicId) {
        Objects.requireNonNull(topicId, "topicId must not be null");
        builder.setTopicID(topicId.toProtobuf());
        return this;
    }

    public TopicMessageQuery setStartTime(Instant startTime) {
        Objects.requireNonNull(startTime, "startTime must not be null");
        builder.setConsensusStartTime(InstantConverter.toProtobuf(startTime));
        return this;
    }

    public TopicMessageQuery setEndTime(Instant endTime) {
        Objects.requireNonNull(endTime, "endTime must not be null");
        builder.setConsensusEndTime(InstantConverter.toProtobuf(endTime));
        return this;
    }

    public TopicMessageQuery setLimit(long limit) {
        builder.setLimit(limit);
        return this;
    }

    public TopicMessageQuery setCompletionHandler(Runnable completionHandler) {
        Objects.requireNonNull(completionHandler, "completionHandler must not be null");
        this.completionHandler = completionHandler;
        return this;
    }

    public TopicMessageQuery setErrorHandler(BiConsumer<Throwable, TopicMessage> errorHandler) {
        Objects.requireNonNull(errorHandler, "errorHandler must not be null");
        this.errorHandler = errorHandler;
        return this;
    }

    public TopicMessageQuery setMaxAttempts(int maxAttempts) {
        if (maxAttempts < 0) {
            throw new IllegalArgumentException("maxAttempts must be positive");
        }
        this.maxAttempts = maxAttempts;
        return this;
    }

    public TopicMessageQuery setMaxBackoff(Duration maxBackoff) {
        if (maxBackoff == null || maxBackoff.toMillis() < 500L) {
            throw new IllegalArgumentException("maxBackoff must be at least 500 ms");
        }
        this.maxBackoff = maxBackoff;
        return this;
    }

    public TopicMessageQuery setRetryHandler(Predicate<Throwable> retryHandler) {
        Objects.requireNonNull(retryHandler, "retryHandler must not be null");
        this.retryHandler = retryHandler;
        return this;
    }

    private void onComplete() {
        var topicId = TopicId.fromProtobuf(builder.getTopicID());
        LOGGER.info("Subscription to topic {} complete", topicId);
    }

    private void onError(Throwable throwable, TopicMessage topicMessage) {
        var topicId = TopicId.fromProtobuf(builder.getTopicID());
        LOGGER.error("Error attempting to subscribe to topic {}:", topicId, throwable);
    }

    /**
     * This method will retry the following scenarios:
     * <p>
     * NOT_FOUND: Can occur when a client creates a topic and attempts to subscribe to it immediately before it
     * is available in the mirror node.
     * <p>
     * UNAVAILABLE: Can occur when the mirror node's database or other downstream components are temporarily down.
     * <p>
     * RESOURCE_EXHAUSTED: Can occur when the mirror node's resources (database, threads, etc.) are temporarily exhausted.
     * <p>
     * INTERNAL: With a gRPC error status description that indicates the stream was reset. Stream resets can sometimes
     * occur when a proxy or load balancer disconnects the client.
     *
     * @param throwable the potentially retryable exception
     * @return if the request should be retried or not
     */
    @SuppressWarnings("MethodCanBeStatic")
    private boolean shouldRetry(Throwable throwable) {
        if (throwable instanceof StatusRuntimeException) {
            var statusRuntimeException = (StatusRuntimeException) throwable;
            var code = statusRuntimeException.getStatus().getCode();
            var description = statusRuntimeException.getStatus().getDescription();

            return (code == Status.Code.NOT_FOUND) ||
                (code == Status.Code.UNAVAILABLE) ||
                (code == Status.Code.RESOURCE_EXHAUSTED) ||
                (code == Status.Code.INTERNAL && description != null && Executable.RST_STREAM.matcher(description).matches());
        }

        return false;
    }

    // TODO: Refactor into a base class when we add more mirror query types
    public SubscriptionHandle subscribe(Client client, Consumer<TopicMessage> onNext) {
        SubscriptionHandle subscriptionHandle = new SubscriptionHandle();
        HashMap<TransactionID, ArrayList<ConsensusTopicResponse>> pendingMessages = new HashMap<>();

        try {
            makeStreamingCall(client, subscriptionHandle, onNext, 0, new AtomicLong(), new AtomicReference<>(), pendingMessages);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        return subscriptionHandle;
    }

    private void makeStreamingCall(
        Client client,
        SubscriptionHandle subscriptionHandle,
        Consumer<TopicMessage> onNext,
        int attempt,
        AtomicLong counter,
        AtomicReference<ConsensusTopicResponse> lastMessage,
        HashMap<TransactionID, ArrayList<ConsensusTopicResponse>> pendingMessages
    ) throws InterruptedException {
        // TODO: check status of channel before using it?
        ClientCall<ConsensusTopicQuery, ConsensusTopicResponse> call =
            client.mirrorNetwork.getNextMirrorNode().getChannelWrapperForExecute().getChannel()
                .newCall(ConsensusServiceGrpc.getSubscribeTopicMethod(), CallOptions.DEFAULT);

        subscriptionHandle.setOnUnsubscribe(() -> {
            call.cancel("unsubscribe", null);
        });

        @Var
        var newBuilder = builder;

        // Update the start time and limit on retry
        if (lastMessage.get() != null) {
            newBuilder = builder.clone();

            if (builder.getLimit() > 0) {
                newBuilder.setLimit(builder.getLimit() - counter.get());
            }

            var lastStartTime = lastMessage.get().getConsensusTimestamp();
            var nextStartTime = Timestamp.newBuilder(lastStartTime).setNanos(lastStartTime.getNanos() + 1);
            newBuilder.setConsensusStartTime(nextStartTime);
        }

        ClientCalls.asyncServerStreamingCall(call, newBuilder.build(), new StreamObserver<>() {
            @Override
            public void onNext(ConsensusTopicResponse consensusTopicResponse) {
                counter.incrementAndGet();
                lastMessage.set(consensusTopicResponse);

                // Short circuit for no chunks or 1/1 chunks
                if (!consensusTopicResponse.hasChunkInfo() || consensusTopicResponse.getChunkInfo().getTotal() == 1) {
                    var message = TopicMessage.ofSingle(consensusTopicResponse);

                    try {
                        onNext.accept(message);
                    } catch (Throwable t) {
                        errorHandler.accept(t, message);
                    }

                    return;
                }

                // get the list of chunks for this pending message
                var initialTransactionID = consensusTopicResponse.getChunkInfo().getInitialTransactionID();

                // Can't use `HashMap.putIfAbsent()` since that method is not available on Android
                if (!pendingMessages.containsKey(initialTransactionID)) {
                    pendingMessages.put(initialTransactionID, new ArrayList<>());
                }

                ArrayList<ConsensusTopicResponse> chunks = pendingMessages.get(initialTransactionID);

                // not possible as we do [putIfAbsent]
                // add our response to the pending chunk list
                Objects.requireNonNull(chunks).add(consensusTopicResponse);

                // if we now have enough chunks, emit
                if (chunks.size() == consensusTopicResponse.getChunkInfo().getTotal()) {
                    var message = TopicMessage.ofMany(chunks);

                    try {
                        onNext.accept(message);
                    } catch (Throwable t) {
                        errorHandler.accept(t, message);
                    }
                }
            }

            @Override
            public void onError(Throwable t) {
                if (attempt >= maxAttempts || !retryHandler.test(t)) {
                    errorHandler.accept(t, null);
                    return;
                }

                var delay = Math.min(500 * (long) Math.pow(2, attempt), maxBackoff.toMillis());
                var topicId = TopicId.fromProtobuf(builder.getTopicID());
                LOGGER.warn("Error subscribing to topic {} during attempt #{}. Waiting {} ms before next attempt: {}",
                    topicId, attempt, delay, t.getMessage());
                call.cancel("unsubscribed", null);

                // Cannot use `CompletableFuture<U>` here since this future is never polled
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }

                try {
                    makeStreamingCall(client, subscriptionHandle, onNext, attempt + 1, counter, lastMessage, pendingMessages);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }

            @Override
            public void onCompleted() {
                completionHandler.run();
            }
        });
    }
}

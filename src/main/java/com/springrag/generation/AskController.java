package com.springrag.generation;

import static com.springrag.auth.CurrentUser.id;

import com.springrag.collection.CollectionService;
import com.springrag.common.ApiException;
import com.springrag.common.ErrorResponse;
import com.springrag.common.RagProperties;
import com.springrag.common.RateLimiter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.http.MediaType;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/collections/{collectionId}/ask")
public class AskController {

    private static final Logger log = LoggerFactory.getLogger(AskController.class);
    private static final long STREAM_TIMEOUT_MS = 180_000;

    /** One earlier message of the conversation; used only to rewrite follow-up questions. */
    public record HistoryTurn(@NotBlank @Pattern(regexp = "user|assistant", message = "must be user or assistant") String role,
                              @NotBlank @Size(max = 4000) String content) {
    }

    public record AskRequest(@NotBlank @Size(max = 2000) String question, @Min(1) @Max(20) Integer topK,
                             @Size(max = 10) List<@Valid HistoryTurn> history) {
    }

    private final CollectionService collections;
    private final AskService ask;
    private final RateLimiter limiter;
    private final RagProperties props;
    private final ThreadPoolTaskExecutor streamExecutor;

    public AskController(CollectionService collections, AskService ask, RateLimiter limiter, RagProperties props,
                         @Qualifier("askStreamExecutor") ThreadPoolTaskExecutor streamExecutor) {
        this.collections = collections;
        this.ask = ask;
        this.limiter = limiter;
        this.props = props;
        this.streamExecutor = streamExecutor;
    }

    @PostMapping
    public AskService.Answer ask(@AuthenticationPrincipal Jwt jwt, @PathVariable long collectionId,
                                 @Valid @RequestBody AskRequest body) {
        long userId = id(jwt);
        prepare(userId, collectionId);
        return ask.ask(userId, collectionId, body.question(), topK(body), turns(body));
    }

    /**
     * Server-sent events: {@code token} events carry the raw text as the model writes it (not yet checked),
     * then one {@code final} event carries the validated answer with citations (it replaces the streamed text),
     * or an {@code error} event if something failed after the stream started.
     */
    @PostMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter askStream(@AuthenticationPrincipal Jwt jwt, @PathVariable long collectionId,
                                @Valid @RequestBody AskRequest body) {
        long userId = id(jwt);
        prepare(userId, collectionId);
        int topK = topK(body);
        List<AskService.Turn> history = turns(body);
        String path = "/api/collections/" + collectionId + "/ask/stream";

        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
        try {
            streamExecutor.execute(() -> {
                try {
                    AskService.Answer answer = ask.askStreaming(userId, collectionId, body.question(), topK, history,
                            text -> send(emitter, "token", Map.of("text", text)));
                    send(emitter, "final", answer);
                    emitter.complete();
                } catch (CancellationException clientGone) {
                    emitter.complete();
                } catch (ApiException e) {
                    sendError(emitter, ErrorResponse.of(e.status(), e.code(), e.getMessage(), path));
                } catch (Exception e) {
                    log.error("Streaming ask failed for collection {}", collectionId, e);
                    sendError(emitter, ErrorResponse.of(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR,
                            "INTERNAL_ERROR", "Unexpected server error.", path));
                }
            });
        } catch (TaskRejectedException e) {
            throw ApiException.busy("Too many answers are being generated right now. Try again in a moment.");
        }
        return emitter;
    }

    private void prepare(long userId, long collectionId) {
        collections.requireOwned(userId, collectionId);
        limiter.check("ask:" + userId, props.rateLimit().askPerMinute());
    }

    private int topK(AskRequest body) {
        return body.topK() != null ? body.topK() : props.topK();
    }

    private static List<AskService.Turn> turns(AskRequest body) {
        return body.history() == null ? List.of()
                : body.history().stream().map(t -> new AskService.Turn(t.role(), t.content())).toList();
    }

    private static void send(SseEmitter emitter, String event, Object data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data, MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException e) {
            // The browser closed the connection: stop generating (and paying for) the rest of the answer.
            throw new CancellationException("client disconnected");
        }
    }

    private static void sendError(SseEmitter emitter, ErrorResponse error) {
        try {
            emitter.send(SseEmitter.event().name("error").data(error, MediaType.APPLICATION_JSON));
            emitter.complete();
        } catch (IOException | IllegalStateException e) {
            emitter.completeWithError(e);
        }
    }
}

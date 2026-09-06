package com.proitbridge.lms.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.proitbridge.lms.domain.AiInteraction;
import com.proitbridge.lms.repo.AiInteractionRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * The model layer. Everything goes through here so the model is one setting, every
 * call is recorded with the model name that produced it, and the LMS keeps working
 * when Ollama is not running: callers get an empty result and fall back to their
 * own rule, rather than a learner being blocked mid chapter.
 */
@Service
public class OllamaService {

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3)).build();
    private final ObjectMapper mapper = new ObjectMapper();
    private final AiInteractionRepository interactions;
    private final String baseUrl;
    private final String model;
    private final int timeoutSeconds;
    private final boolean enabled;

    public OllamaService(AiInteractionRepository interactions,
                         @Value("${lms.ai.ollama.url}") String baseUrl,
                         @Value("${lms.ai.ollama.model}") String model,
                         @Value("${lms.ai.ollama.timeout-seconds}") int timeoutSeconds,
                         @Value("${lms.ai.enabled}") boolean enabled) {
        this.interactions = interactions; this.baseUrl = baseUrl; this.model = model;
        this.timeoutSeconds = timeoutSeconds; this.enabled = enabled;
    }

    public String modelName() { return model; }
    public boolean isEnabled() { return enabled; }

    /** Returns empty rather than throwing: the caller decides what to do without a model. */
    public Optional<String> complete(String system, String prompt, String kind,
                                     String learnerId, String chapterId) {
        if (!enabled) return Optional.empty();
        long start = System.currentTimeMillis();
        try {
            String body = mapper.writeValueAsString(Map.of(
                    "model", model,
                    "prompt", prompt,
                    "system", system,
                    "stream", false,
                    "options", Map.of("temperature", 0.2, "num_predict", 400)));
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/generate"))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) return record(kind, learnerId, chapterId, prompt, null, start, true);
            JsonNode node = mapper.readTree(res.body());
            String text = node.path("response").asText("");
            return record(kind, learnerId, chapterId, prompt, text.isBlank() ? null : text, start, false);
        } catch (Exception e) {
            return record(kind, learnerId, chapterId, prompt, null, start, true);
        }
    }

    /** Models wrap JSON in prose and fences often enough that this is worth doing once. */
    public Optional<JsonNode> completeJson(String system, String prompt, String kind,
                                           String learnerId, String chapterId) {
        return complete(system + " Reply with JSON only, no prose and no code fences.",
                prompt, kind, learnerId, chapterId)
                .flatMap(text -> {
                    try {
                        String cleaned = text.replaceAll("(?s)```(json)?", "").trim();
                        int a = cleaned.indexOf('{'), b = cleaned.lastIndexOf('}');
                        if (a < 0 || b <= a) return Optional.empty();
                        return Optional.of(mapper.readTree(cleaned.substring(a, b + 1)));
                    } catch (Exception e) {
                        return Optional.empty();
                    }
                });
    }

    private Optional<String> record(String kind, String learnerId, String chapterId,
                                    String prompt, String response, long start, boolean fallback) {
        AiInteraction i = new AiInteraction();
        i.setKind(kind);
        i.setLearnerId(learnerId);
        i.setChapterId(chapterId);
        i.setPrompt(prompt);
        i.setResponse(response);
        i.setModel(model);
        i.setFallback(fallback);
        i.setLatencyMs(System.currentTimeMillis() - start);
        interactions.save(i);
        return Optional.ofNullable(response);
    }
}

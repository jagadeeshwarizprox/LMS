package com.proitbridge.lms.service;

import com.proitbridge.lms.domain.Faq;
import com.proitbridge.lms.repo.ChapterRepository;
import com.proitbridge.lms.repo.TopicRepository;
import com.proitbridge.lms.repo.FaqRepository;
import com.proitbridge.lms.repo.ModuleRepository;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/** Answers where the question is asked, not in a help centre nobody opens. */
@Service
public class FaqService {

    private final FaqRepository faqs;
    private final ChapterRepository chapters;
    private final TopicRepository topics;
    private final ModuleRepository modules;
    private final OllamaService ai;
    private final ActivityService activity;

    public FaqService(FaqRepository faqs, ChapterRepository chapters, TopicRepository topics,
                      ModuleRepository modules,
                      OllamaService ai, ActivityService activity) {
        this.faqs = faqs; this.chapters = chapters; this.topics = topics; this.modules = modules;
        this.ai = ai; this.activity = activity;
    }

    public List<Faq> forPlacement(String placement, String track) {
        return faqs.findByPlacementAndActiveTrueOrderByPositionAsc(placement).stream()
                .filter(f -> "BOTH".equals(f.getTrackScope()) || f.getTrackScope().equals(track))
                .toList();
    }

    public List<Faq> all() { return faqs.findByActiveTrueOrderByPositionAsc(); }

    /**
     * One box over questions, chapter titles and topic titles.
     *
     * A learner searching for "groupby" is naming a topic, not a chapter, so searching
     * only chapter titles missed most of what they were looking for.
     */
    public Map<String, Object> search(String q, String track) {
        String needle = q == null ? "" : q.trim().toLowerCase();
        if (needle.length() < 2) return Map.of("faqs", List.of(), "chapters", List.of(), "topics", List.of());

        List<Faq> hits = faqs.findByActiveTrueOrderByPositionAsc().stream()
                .filter(f -> "BOTH".equals(f.getTrackScope()) || f.getTrackScope().equals(track))
                .filter(f -> f.getQuestion().toLowerCase().contains(needle)
                        || f.getAnswer().toLowerCase().contains(needle))
                .limit(8).toList();

        List<Map<String, Object>> chapterHits = chapters.findAll().stream()
                .filter(c -> c.getTitle().toLowerCase().contains(needle))
                .limit(8)
                .map(c -> Map.<String, Object>of(
                        "id", c.getId(),
                        "kind", "chapter",
                        "title", c.getTitle(),
                        "module", modules.findById(c.getModuleId()).map(t -> t.getName()).orElse("")))
                .collect(Collectors.toList());

        List<Map<String, Object>> topicHits = topics.findAll().stream()
                .filter(t -> t.getTitle().toLowerCase().contains(needle))
                .limit(8)
                .map(t -> Map.<String, Object>of(
                        "id", t.getId(),
                        "kind", "topic",
                        "chapterId", t.getChapterId(),
                        "title", t.getTitle(),
                        "module", modules.findById(t.getModuleId()).map(m -> m.getName()).orElse("")))
                .collect(Collectors.toList());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("faqs", hits);
        out.put("chapters", chapterHits);
        out.put("topics", topicHits);

        // only when nothing curated matches, and always labelled as model written
        if (hits.isEmpty() && chapterHits.isEmpty() && topicHits.isEmpty() && ai.isEnabled()) {
            ai.complete(
                "You answer questions about the ProITBridge LMS. Be brief and factual. "
                + "If the question is not about the LMS, say it is better asked in the WhatsApp group.",
                q, "FAQ", null, null)
              .ifPresent(a -> out.put("aiAnswer", a));
        }
        return out;
    }

    public Faq save(Faq body, String actorEmail) {
        Faq saved = faqs.save(body);
        activity.log(null, actorEmail, "SAVE_FAQ", "faq", saved.getQuestion());
        return saved;
    }

    public void delete(String id, String actorEmail) {
        faqs.deleteById(id);
        activity.log(null, actorEmail, "DELETE_FAQ", "faq", id);
    }
}

package com.proitbridge.lms.web;

import com.proitbridge.lms.domain.Faq;
import com.proitbridge.lms.security.CurrentUser;
import com.proitbridge.lms.service.FaqService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
public class FaqController {

    private final FaqService faqs;
    private final CurrentUser current;

    public FaqController(FaqService faqs, CurrentUser current) {
        this.faqs = faqs;
        this.current = current;
    }

    /** The sign in screen needs answers before anyone is signed in. */
    @GetMapping("/api/public/faqs")
    public List<Faq> login() { return faqs.forPlacement("LOGIN", "BOTH"); }

    @GetMapping("/api/faqs")
    public List<Faq> forPlacement(@RequestParam(required = false) String placement,
                                  @RequestParam(defaultValue = "BOTH") String track) {
        return placement == null ? faqs.all() : faqs.forPlacement(placement, track);
    }

    @GetMapping("/api/faqs/search")
    public Map<String, Object> search(@RequestParam String q,
                                      @RequestParam(defaultValue = "BOTH") String track) {
        return faqs.search(q, track);
    }

    @PostMapping("/api/super/faqs")
    public Faq save(@RequestBody Faq body) { return faqs.save(body, current.get().email()); }

    @DeleteMapping("/api/super/faqs/{id}")
    public Map<String, Object> delete(@PathVariable String id) {
        faqs.delete(id, current.get().email());
        return Map.of("deleted", true);
    }
}

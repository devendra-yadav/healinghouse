package com.clinic.healinghouse.controller;

import com.clinic.healinghouse.entity.Tag;
import com.clinic.healinghouse.service.TagService;
import com.clinic.healinghouse.util.PaginationUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;

@Controller
@RequestMapping("/tags")
@RequiredArgsConstructor
public class TagController {

    private static final int MAX_SUGGESTIONS = 10;

    private final TagService tagService;

    @GetMapping
    public String list(@RequestParam(defaultValue = "0") int page,
                       @RequestParam(defaultValue = "20") int size,
                       Model model) {
        int pageSize = PaginationUtil.clampPageSize(size);
        page = PaginationUtil.clampPage(page);
        model.addAttribute("tagUsages", tagService.findAllWithUsage(PageRequest.of(page, pageSize)));
        model.addAttribute("allTags", tagService.findAll());
        model.addAttribute("pageTitle", "Tags");
        return "tags/list";
    }

    /** JSON autocomplete endpoint backing the tag chip input on Service/Product forms. */
    @GetMapping("/search")
    @ResponseBody
    public List<String> search(@RequestParam(required = false) String q) {
        return tagService.search(q).stream()
                .map(Tag::getName)
                .limit(MAX_SUGGESTIONS)
                .toList();
    }

    @PostMapping("/{id}/rename")
    public String rename(@PathVariable Long id, @RequestParam String name, RedirectAttributes ra) {
        try {
            tagService.rename(id, name);
            ra.addFlashAttribute("successMessage", "Tag renamed successfully.");
        } catch (Exception e) {
            ra.addFlashAttribute("errorMessage", e.getMessage() != null ? e.getMessage() : "Could not rename tag.");
        }
        return "redirect:/tags";
    }

    @PostMapping("/merge")
    public String merge(@RequestParam Long sourceId, @RequestParam Long targetId, RedirectAttributes ra) {
        try {
            tagService.merge(sourceId, targetId);
            ra.addFlashAttribute("successMessage", "Tags merged successfully.");
        } catch (Exception e) {
            ra.addFlashAttribute("errorMessage", e.getMessage() != null ? e.getMessage() : "Could not merge tags.");
        }
        return "redirect:/tags";
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id, RedirectAttributes ra) {
        try {
            tagService.delete(id);
            ra.addFlashAttribute("successMessage", "Tag deleted successfully.");
        } catch (Exception e) {
            ra.addFlashAttribute("errorMessage", e.getMessage() != null ? e.getMessage() : "Could not delete tag.");
        }
        return "redirect:/tags";
    }
}

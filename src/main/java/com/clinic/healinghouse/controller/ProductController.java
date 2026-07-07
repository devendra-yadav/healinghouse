package com.clinic.healinghouse.controller;

import com.clinic.healinghouse.entity.Product;
import com.clinic.healinghouse.entity.Tag;
import com.clinic.healinghouse.service.ProductService;
import com.clinic.healinghouse.service.TagService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.StringUtils;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;
    private final TagService tagService;

    @GetMapping
    public String list(@RequestParam(required = false) String q,
                       @RequestParam(required = false) String tag,
                       Model model) {
        List<Product> products;
        if (StringUtils.hasText(tag)) {
            products = productService.findByTag(tag);
        } else if (StringUtils.hasText(q)) {
            products = productService.search(q);
        } else {
            products = productService.findAll();
        }
        long lowStockCount = products.stream().filter(Product::isLowStock).count();
        model.addAttribute("products", products);
        model.addAttribute("lowStockCount", lowStockCount);
        model.addAttribute("allTags", tagService.findAll());
        model.addAttribute("selectedTag", tag);
        model.addAttribute("q", q);
        model.addAttribute("pageTitle", "Products");
        return "products/list";
    }

    @GetMapping("/new")
    public String newForm(Model model) {
        model.addAttribute("product", Product.builder().reorderLevel(5).build());
        model.addAttribute("existingTagNames", "");
        model.addAttribute("pageTitle", "New Product");
        return "products/form";
    }

    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable Long id, Model model) {
        Product product = productService.getById(id);
        model.addAttribute("product", product);
        model.addAttribute("existingTagNames", joinTagNames(product.getSortedTags()));
        model.addAttribute("pageTitle", "Edit Product");
        return "products/form";
    }

    @PostMapping("/save")
    public String save(@Valid @ModelAttribute("product") Product product,
                       BindingResult result,
                       @RequestParam(required = false) String tagNames,
                       Model model,
                       RedirectAttributes ra) {
        if (result.hasErrors()) {
            model.addAttribute("existingTagNames", tagNames);
            model.addAttribute("pageTitle", product.getId() == null ? "New Product" : "Edit Product");
            return "products/form";
        }
        productService.save(product, parseTagNames(tagNames));
        ra.addFlashAttribute("successMessage", "Product saved successfully.");
        return "redirect:/products";
    }

    private List<String> parseTagNames(String raw) {
        if (!StringUtils.hasText(raw)) return List.of();
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .toList();
    }

    private String joinTagNames(List<Tag> tags) {
        return tags.stream().map(Tag::getName).collect(Collectors.joining(", "));
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id, RedirectAttributes ra) {
        productService.deactivate(id);
        ra.addFlashAttribute("successMessage", "Product deactivated successfully.");
        return "redirect:/products";
    }
}
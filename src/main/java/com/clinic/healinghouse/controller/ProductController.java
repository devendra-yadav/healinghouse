package com.clinic.healinghouse.controller;

import com.clinic.healinghouse.entity.Module;
import com.clinic.healinghouse.entity.PermissionAction;
import com.clinic.healinghouse.entity.Product;
import com.clinic.healinghouse.entity.Tag;
import com.clinic.healinghouse.security.PermissionService;
import com.clinic.healinghouse.security.RequiresPermission;
import com.clinic.healinghouse.service.ProductService;
import com.clinic.healinghouse.service.TagService;
import com.clinic.healinghouse.util.PaginationUtil;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
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
    private final PaginationUtil paginationUtil;
    private final PermissionService permissionService;

    @RequiresPermission(module = Module.PRODUCTS, action = PermissionAction.VIEW)
    @GetMapping
    public String list(@RequestParam(required = false) String q,
                       @RequestParam(required = false) String tag,
                       @RequestParam(defaultValue = "false") boolean showInactive,
                       @RequestParam(defaultValue = "0") int page,
                       @RequestParam(defaultValue = "20") int size,
                       Model model) {
        int pageSize = paginationUtil.clampPageSize(size);
        page = paginationUtil.clampPage(page);
        boolean hasFilter = StringUtils.hasText(q) || StringUtils.hasText(tag);
        model.addAttribute("products", (showInactive && !hasFilter)
                ? productService.findAllIncludingInactive(PageRequest.of(page, pageSize, Sort.by("name")))
                : productService.search(q, tag, PageRequest.of(page, pageSize)));
        model.addAttribute("lowStockCount", productService.findLowStock().size());
        model.addAttribute("allTags", tagService.findAll());
        model.addAttribute("selectedTag", tag);
        model.addAttribute("q", q);
        model.addAttribute("showInactive", showInactive);
        model.addAttribute("pageTitle", "Products");
        return "products/list";
    }

    @RequiresPermission(module = Module.PRODUCTS, action = PermissionAction.CREATE)
    @GetMapping("/new")
    public String newForm(Model model) {
        model.addAttribute("product", Product.builder().reorderLevel(5).build());
        model.addAttribute("existingTagNames", "");
        model.addAttribute("pageTitle", "New Product");
        return "products/form";
    }

    @RequiresPermission(module = Module.PRODUCTS, action = PermissionAction.VIEW)
    @GetMapping("/{id}")
    public String detail(@PathVariable Long id, Model model) {
        Product product = productService.getById(id);
        model.addAttribute("product", product);
        model.addAttribute("pageTitle", product.getName());
        return "products/detail";
    }

    @RequiresPermission(module = Module.PRODUCTS, action = PermissionAction.EDIT)
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
        permissionService.require(Module.PRODUCTS, product.getId() == null ? PermissionAction.CREATE : PermissionAction.EDIT);
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

    @RequiresPermission(module = Module.PRODUCTS, action = PermissionAction.DELETE)
    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id, RedirectAttributes ra) {
        var comboImpact = productService.deactivate(id);
        ra.addFlashAttribute("successMessage", "Product deactivated successfully." + comboImpact.describe());
        return "redirect:/products";
    }

    @RequiresPermission(module = Module.PRODUCTS, action = PermissionAction.DELETE)
    @PostMapping("/{id}/activate")
    public String activate(@PathVariable Long id, RedirectAttributes ra) {
        productService.activate(id);
        ra.addFlashAttribute("successMessage", "Product reactivated successfully.");
        return "redirect:/products";
    }

    @RequiresPermission(module = Module.PRODUCTS, action = PermissionAction.APPROVE)
    @PostMapping("/{id}/delete-permanent")
    public String deletePermanent(@PathVariable Long id, RedirectAttributes ra) {
        try {
            productService.permanentlyDelete(id);
            ra.addFlashAttribute("successMessage", "Product permanently deleted.");
        } catch (IllegalArgumentException ex) {
            ra.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/products?showInactive=true";
    }
}
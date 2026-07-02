package com.clinic.healinghouse.controller;

import com.clinic.healinghouse.entity.Product;
import com.clinic.healinghouse.service.ProductService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.StringUtils;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;

@Controller
@RequestMapping("/products")
@RequiredArgsConstructor
public class ProductController {

    static final List<String> CATEGORIES = List.of(
            "Herbal Supplement", "Oil", "Tea", "Detox Kit", "Capsule", "Other"
    );

    private final ProductService productService;

    @GetMapping
    public String list(@RequestParam(required = false) String q,
                       @RequestParam(required = false) String category,
                       Model model) {
        List<Product> products;
        if (StringUtils.hasText(category)) {
            products = productService.findByCategory(category);
        } else if (StringUtils.hasText(q)) {
            products = productService.search(q);
        } else {
            products = productService.findAll();
        }
        long lowStockCount = products.stream().filter(Product::isLowStock).count();
        model.addAttribute("products", products);
        model.addAttribute("lowStockCount", lowStockCount);
        model.addAttribute("categories", CATEGORIES);
        model.addAttribute("selectedCategory", category);
        model.addAttribute("q", q);
        model.addAttribute("pageTitle", "Products");
        return "products/list";
    }

    @GetMapping("/new")
    public String newForm(Model model) {
        model.addAttribute("product", Product.builder().reorderLevel(5).build());
        model.addAttribute("categories", CATEGORIES);
        model.addAttribute("pageTitle", "New Product");
        return "products/form";
    }

    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable Long id, Model model) {
        model.addAttribute("product", productService.getById(id));
        model.addAttribute("categories", CATEGORIES);
        model.addAttribute("pageTitle", "Edit Product");
        return "products/form";
    }

    @PostMapping("/save")
    public String save(@Valid @ModelAttribute("product") Product product,
                       BindingResult result,
                       Model model,
                       RedirectAttributes ra) {
        if (result.hasErrors()) {
            model.addAttribute("categories", CATEGORIES);
            model.addAttribute("pageTitle", product.getId() == null ? "New Product" : "Edit Product");
            return "products/form";
        }
        productService.save(product);
        ra.addFlashAttribute("successMessage", "Product saved successfully.");
        return "redirect:/products";
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id, RedirectAttributes ra) {
        productService.deactivate(id);
        ra.addFlashAttribute("successMessage", "Product deactivated successfully.");
        return "redirect:/products";
    }
}
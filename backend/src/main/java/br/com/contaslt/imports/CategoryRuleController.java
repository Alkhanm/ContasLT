package br.com.contaslt.imports;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import br.com.contaslt.category.Category;
import br.com.contaslt.category.CategoryService;
import br.com.contaslt.common.NotFoundException;
import br.com.contaslt.common.TextNormalizer;
import br.com.contaslt.imports.ImportDtos.CategoryRuleDto;
import br.com.contaslt.imports.ImportDtos.CategoryRuleRequest;
import br.com.contaslt.user.CurrentUser;
import br.com.contaslt.user.User;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/category-rules")
public class CategoryRuleController {

    private final CategoryRuleRepository rules;
    private final CategoryService categories;
    private final CurrentUser currentUser;

    public CategoryRuleController(CategoryRuleRepository rules, CategoryService categories, CurrentUser currentUser) {
        this.rules = rules;
        this.categories = categories;
        this.currentUser = currentUser;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public List<CategoryRuleDto> list() {
        return rules.findByUserIdOrderByKeywordAsc(currentUser.id()).stream().map(CategoryRuleController::toDto).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public CategoryRuleDto create(@Valid @RequestBody CategoryRuleRequest req) {
        User user = currentUser.get();
        Category category = categories.require(user.getId(), req.categoryId());
        String keyword = TextNormalizer.truncate(TextNormalizer.normalize(req.keyword()), 120);
        CategoryRule rule = rules.findByUserIdAndKeyword(user.getId(), keyword).orElseGet(() -> {
            CategoryRule r = new CategoryRule();
            r.setUser(user);
            r.setKeyword(keyword);
            return r;
        });
        rule.setCategory(category);
        return toDto(rules.save(rule));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void delete(@PathVariable Long id) {
        CategoryRule rule = rules.findByIdAndUserId(id, currentUser.id())
            .orElseThrow(() -> new NotFoundException("Regra não encontrada"));
        rules.delete(rule);
    }

    private static CategoryRuleDto toDto(CategoryRule r) {
        Category c = r.getCategory();
        return new CategoryRuleDto(r.getId(), r.getKeyword(), c.getId(), c.getName(), c.getColor(), c.getIcon());
    }
}

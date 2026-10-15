package br.com.contaslt.category;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import br.com.contaslt.category.CategoryDtos.CategoryDto;
import br.com.contaslt.category.CategoryDtos.CategoryRequest;
import br.com.contaslt.user.CurrentUser;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/categories")
public class CategoryController {

    private final CategoryService categories;
    private final CurrentUser currentUser;

    public CategoryController(CategoryService categories, CurrentUser currentUser) {
        this.categories = categories;
        this.currentUser = currentUser;
    }

    @GetMapping
    public List<CategoryDto> list(@RequestParam(defaultValue = "false") boolean includeArchived) {
        return categories.list(currentUser.id(), includeArchived);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CategoryDto create(@Valid @RequestBody CategoryRequest req) {
        return categories.create(currentUser.get(), req);
    }

    @PutMapping("/{id}")
    public CategoryDto update(@PathVariable Long id, @Valid @RequestBody CategoryRequest req) {
        return categories.update(currentUser.id(), id, req);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        categories.delete(currentUser.id(), id);
    }
}
